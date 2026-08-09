package com.ivi.car.navigation.viewmodel

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.location.LocationListener
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.ivi.car.navigation.controller.NavigationManager
import com.ivi.car.navigation.model.NavigationResultCode
import com.ivi.car.navigation.model.NavigationState
import com.ivi.car.navigation.model.NavigationSuggestion
import com.ivi.car.navigation.model.HomeLocation
import com.ivi.car.navigation.model.NearbyCategory
import com.ivi.car.navigation.model.Navigation
import com.ivi.car.navigation.model.WorkLocation
import com.ivi.car.navigation.util.Utils
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.replay.route.ReplayRouteMapper
import com.mapbox.search.autocomplete.PlaceAutocomplete
import com.mapbox.search.autocomplete.PlaceAutocompleteOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fauto.car.FAutoCar
import fauto.car.clustercontrol.FAutoCarClusterControlManager
import ivi.navigation.IviNavigationEventManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject


@HiltViewModel
class NaviViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel(), LocationListener,
    IviNavigationEventManager.INavigationEventListener {
    private val TAG = this.javaClass.name
    private lateinit var mapboxNavigation: MapboxNavigation
    lateinit var iviNavigationEventManager: IviNavigationEventManager

    private val _destinationPlace: MutableLiveData<String> = MutableLiveData()
    val destinationPlace: LiveData<String> get() = _destinationPlace
    private val _destinationPoi: MutableLiveData<Point> = MutableLiveData()
    val destinationPoi: LiveData<Point> get() = _destinationPoi
    private val _nearBy: MutableLiveData<Pair<String, Int>> = MutableLiveData()
    val nearBy: LiveData<Pair<String, Int>> get() = _nearBy
    var currentPoint: Point? = null
        set(value) {
            field = value
            NavigationManager.updateCurrentPoint(value)
        }
    private val _suggestions = MutableLiveData<List<NavigationSuggestion>>(emptyList())
    val suggestions: LiveData<List<NavigationSuggestion>> get() = _suggestions
    private val _navigationState = MutableLiveData<NavigationState>()
    val navigationState: LiveData<NavigationState> get() = _navigationState
    private val _home = MutableLiveData<HomeLocation?>()
    val home: LiveData<HomeLocation?> get() = _home
    private val _work = MutableLiveData<WorkLocation?>()
    val work: LiveData<WorkLocation?> get() = _work
    private val _searchInProgress = MutableLiveData(false)
    val searchInProgress: LiveData<Boolean> get() = _searchInProgress
    private val _searchMessage = MutableLiveData<String?>()
    val searchMessage: LiveData<String?> get() = _searchMessage
    private val _selectedSuggestion = MutableLiveData<NavigationSuggestion?>()
    val selectedSuggestion: LiveData<NavigationSuggestion?> get() = _selectedSuggestion
    private val _detailInProgress = MutableLiveData(false)
    val detailInProgress: LiveData<Boolean> get() = _detailInProgress
    private var nearbySearchJob: Job? = null
    private var detailJob: Job? = null
    var gson: Gson = Gson()

    private var car: FAutoCar? = null
    private var carClusterManager: FAutoCarClusterControlManager? = null
    private var assistanceConnectionRequested = false
    private var assistanceListenerRegistered = false

    val intentFilter = IntentFilter().apply {
        addAction(INTENT_ACTION_REQUEST_ROUTE)
        addAction(INTENT_ACTION_FIND_NEARBY)
    }

    companion object {
        const val INTENT_ACTION_REQUEST_ROUTE = "com.ivi.action.REQUEST_ROUTE_BY_PLACE"
        const val INTENT_ACTION_FIND_NEARBY = "com.ivi.action.REQUEST_FIND_NEARBY"
        private const val DEFAULT_SEARCH_LIMIT = 5
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when(intent?.action) {
                INTENT_ACTION_FIND_NEARBY -> {
                    val category = intent.getStringExtra("category") ?: "restaurant"
                    val limit = intent.getIntExtra("limit", 5)
                    searchNearBy(category, limit)
                }
                INTENT_ACTION_REQUEST_ROUTE -> {
                    val place = intent.getStringExtra("place")
                    _destinationPlace.postValue(place ?: "")
                }
            }
        }
    }

    private val mFAutoCarConnectionListener: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, iBinder: IBinder?) {
            try {
                Log.d("TurnByTurn", "onServiceConnected to $name")
                carClusterManager =
                    car?.getFAutoCarManager(FAutoCarClusterControlManager.CLUSTERCONTROL_SERVICE)
                        as? FAutoCarClusterControlManager
            } catch (e: RemoteException) {
                Log.e("TurnByTurn", "Failed to link to death recipient", e)
            } catch (e: DeadObjectException) {
                Log.e("TurnByTurn", "Failed to cast to death ", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            carClusterManager = null
            car = null
        }
    }

    init {
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(receiver, intentFilter)
        viewModelScope.launch {
            NavigationManager.state.collect { state ->
                _navigationState.value = state
                _suggestions.value = state.suggestions
                _home.value = state.home
                _work.value = state.work
                applyFixedSimulationSpeedIfNavigationReady()
            }
        }
    }

    fun initNavigation() {
        MapboxNavigationApp.setup(
            NavigationOptions.Builder(context).build()
        )
    }

    fun setMapBoxNavigation(mapboxNavigation: MapboxNavigation) {
        this.mapboxNavigation = mapboxNavigation
        NavigationManager.attachNavigation(mapboxNavigation)
        applyFixedSimulationSpeedIfNavigationReady()
    }

    fun connectService() {
        if (!assistanceConnectionRequested) {
            assistanceConnectionRequested = true
            iviNavigationEventManager = IviNavigationEventManager.getInstance(context)
            iviNavigationEventManager.connectService(object :
                IviNavigationEventManager.IServiceConnection {
                override fun onServiceConnected() {
                    if (!assistanceListenerRegistered) {
                        iviNavigationEventManager.registerNavigationEventListener(this@NaviViewModel)
                        assistanceListenerRegistered = true
                    }
                }

                override fun onServiceDisconnected() {
                    assistanceListenerRegistered = false
                    assistanceConnectionRequested = false
                }
            })
        }
        // Connect to CarAPI
        if (car == null) {
            car = FAutoCar.createFAutoCar(context, mFAutoCarConnectionListener)
            car?.connect()
        }
    }

    fun findRoute(
        originLocation: Location?,
        destination: Point,
        isAssistanceRequest: Boolean = false
    ) {
        val result = NavigationManager.findRoute(
            originLocation = originLocation,
            destinationPoint = destination,
            completion = { success, message ->
                if (isAssistanceRequest) {
                    if (success) {
                        notifyAssistanceSuccess()
                    } else {
                        notifyAssistanceError(102, message ?: "Request routes failed")
                    }
                }
            }
        )
        if (isAssistanceRequest &&
            result != NavigationResultCode.ACCEPTED
        ) {
            notifyAssistanceError(104, "Current location or navigation is unavailable")
        }
    }

    fun sendNav(navigation: Navigation? = null) {
        val intent = Intent("com.android.systemui.car.systembar.NAVIGATION_UPDATE")
        if (navigation != null) {
            val data = Utils.convertDataToTurnByTurn(navigation)
            Log.d(TAG, "Sending navigation update to SystemUI")
            intent.putExtra("is_visible", true)
            intent.putExtra("title", data.turnPointName)
            intent.putExtra(
                "distance",
                data.distanceToTurnPointTxt + " " + data.distanceToTurnPointUnitTxt
            )
            intent.putExtra("icon", data.type?.lowercase())
        } else {
            intent.putExtra("is_visible", false)
        }
        context.sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(0))
    }

    fun sendNavDataToSomeIp(navigation: Navigation? = null) {
        if (navigation == null) {
            return
        }
        val messageJson = JSONObject()
        val navigationJsonStr = gson.toJson(navigation)
        messageJson.put("Navigation", JSONObject(navigationJsonStr))
        Log.d("TurnByTurn", "Sending navigation update to cluster")
        carClusterManager?.fireNavPictureUpdatesEvent(FAutoCarClusterControlManager.PICTURE_UPDATE_EVENT_HEADER_DISPLAY_AVAILABLE, messageJson.toString())
    }

    fun clearNavigationRoutes() {
        // clear
        mapboxNavigation.setNavigationRoutes(listOf())
    }

    @OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
    fun startSimulation(route: DirectionsRoute, replayRouteMapper: ReplayRouteMapper) {
        val replayData = replayRouteMapper.mapDirectionsRouteGeometry(route)
        val firstEvent = replayData.firstOrNull() ?: return
        mapboxNavigation.startReplayTripSession()
        mapboxNavigation.mapboxReplayer.stop()
        mapboxNavigation.mapboxReplayer.clearEvents()
        mapboxNavigation.mapboxReplayer.pushEvents(replayData)
        mapboxNavigation.mapboxReplayer.seekTo(firstEvent)
        mapboxNavigation.mapboxReplayer.play()
        applyFixedSimulationSpeedIfNavigationReady()
        NavigationManager.markSimulationStarted()
    }

    @OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
    fun stopSimulation() {
        mapboxNavigation.mapboxReplayer.stop()
        mapboxNavigation.mapboxReplayer.clearEvents()
        NavigationManager.stopNavigation()
    }

    @OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
    private fun applyFixedSimulationSpeedIfNavigationReady() {
        if (!::mapboxNavigation.isInitialized) return
        // NAV-005/006 are out of scope. Terrain/demo-mode speed changes stay disabled.
        mapboxNavigation.mapboxReplayer.playbackSpeed(1.0)
    }

    fun selectSuggestion(suggestionId: String): Int {
        return NavigationManager.selectSuggestion(suggestionId)
    }

    fun showSuggestionDetails(suggestionId: String): Int {
        val suggestion = _suggestions.value.orEmpty()
            .firstOrNull { it.suggestionId == suggestionId }
            ?: return NavigationResultCode.NOT_FOUND
        _selectedSuggestion.value = suggestion
        detailJob?.cancel()
        _detailInProgress.value = true
        detailJob = viewModelScope.launch {
            val enriched = NavigationManager.loadSuggestionDetails(suggestionId) ?: suggestion
            if (_selectedSuggestion.value?.suggestionId == suggestionId) {
                _selectedSuggestion.value = enriched
                _detailInProgress.value = false
            }
        }
        return NavigationResultCode.ACCEPTED
    }

    fun clearSuggestionDetails() {
        detailJob?.cancel()
        _detailInProgress.value = false
        _selectedSuggestion.value = null
    }

    fun setRoute(destination: String): Int {
        return NavigationManager.setRoute(destination)
    }

    fun searchNearby(
        categoryCode: Int,
        limit: Int = DEFAULT_SEARCH_LIMIT,
        sortCode: Int? = null
    ) {
        nearbySearchJob?.cancel()
        nearbySearchJob = viewModelScope.launch {
            _searchInProgress.value = true
            _searchMessage.value = null
            val result = withContext(Dispatchers.IO) {
                NavigationManager.searchNearby(categoryCode, limit, sortCode)
            }
            _searchMessage.value = result.message ?: "${result.candidates.size} places found"
            _searchInProgress.value = false
        }
    }

    fun searchDestinations(query: String, limit: Int = DEFAULT_SEARCH_LIMIT) {
        nearbySearchJob?.cancel()
        nearbySearchJob = viewModelScope.launch {
            _searchInProgress.value = true
            _searchMessage.value = null
            val result = withContext(Dispatchers.IO) {
                NavigationManager.searchDestinationSuggestions(query, limit)
            }
            _searchMessage.value = result.message
                ?: "${result.candidates.size} destinations found"
            _searchInProgress.value = false
        }
    }

    fun clearSearch() {
        nearbySearchJob?.cancel()
        clearSuggestionDetails()
        _searchInProgress.value = false
        _searchMessage.value = null
        NavigationManager.clearSearchResults()
    }

    fun useManualOrigin(point: Point) {
        currentPoint = point
        _searchMessage.value = "Map center is being used as the demo origin"
    }

    fun startNavigatingHome(): Int {
        return NavigationManager.startNavigatingHome()
    }

    fun startNavigatingWork(): Int {
        return NavigationManager.startNavigatingWork()
    }

    fun setHome(name: String, address: String?, point: Point) {
        NavigationManager.setHome(name, address, point)
    }

    fun clearHome() {
        NavigationManager.clearHome()
    }

    fun setWork(name: String, address: String?, point: Point) {
        NavigationManager.setWork(name, address, point)
    }

    fun clearWork() {
        NavigationManager.clearWork()
    }

    private fun searchNearBy(category: String, limit: Int) {
        val mappedCategory = NearbyCategory.fromName(category)
        if (mappedCategory == null) {
            notifyAssistanceError(105, "Nearby category is empty")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = NavigationManager.searchNearby(
                categoryCode = mappedCategory.code,
                limit = if (limit <= 0) DEFAULT_SEARCH_LIMIT else limit.coerceAtMost(20),
                sortCode = null
            )
            if (result.resultCode == NavigationResultCode.ACCEPTED) {
                notifyAssistanceMessage(result.toJson())
            } else {
                notifyAssistanceError(103, "No category search results")
            }
        }
    }

    private lateinit var placeAutocomplete: PlaceAutocomplete

    fun searchPlace(originLocation: Location?, place: String) {
        if (originLocation == null) {
            notifyAssistanceError(104, "Current location is unavailable")
            return
        }
        if (place.isBlank()) {
            notifyAssistanceError(106, "Destination is empty")
            return
        }
        placeAutocomplete = PlaceAutocomplete.create()
        viewModelScope.launch(Dispatchers.IO) {
            val response = placeAutocomplete.suggestions(
                query = place,
                options = PlaceAutocompleteOptions()
            )
            if (response.isValue) {
                val suggestions = requireNotNull(response.value)

                Log.i("SearchApiExample", "Place Autocomplete suggestion count: ${suggestions.size}")

                if (suggestions.isNotEmpty()) {
                    // Supposing that a user has selected (clicked in UI) the first suggestion
                    val selectedSuggestion = suggestions.first()

                    Log.i("SearchApiExample", "Selecting first suggestion...")

                    val selectionResponse = placeAutocomplete.select(selectedSuggestion)
                    selectionResponse.onValue { result ->
                        Log.i("SearchApiExample", "Place Autocomplete destination selected")
                        findRoute(originLocation, result.coordinate, true)
                    }.onError { error ->
                        Log.i("SearchApiExample", "An error occurred during selection", error)
                        notifyAssistanceError(107, "Unable to select destination")
                    }
                } else {
                    notifyAssistanceError(108, "No destination suggestions")
                }
            } else {
                Log.i("SearchApiExample", "Place Autocomplete error", response.error)
                notifyAssistanceError(109, "Destination search failed")
            }
        }
    }

    fun disconnectCarService() {
        car?.disconnect()
        car = null
        carClusterManager = null
    }

    private fun notifyAssistanceError(errorCode: Int, message: String) {
        if (!::iviNavigationEventManager.isInitialized) {
            Log.w(TAG, "Assistance service is unavailable: $errorCode - $message")
            return
        }
        try {
            iviNavigationEventManager.setError(errorCode, message)
        } catch (error: RemoteException) {
            Log.e(TAG, "Unable to report assistance error", error)
        }
    }

    private fun notifyAssistanceSuccess() {
        if (!::iviNavigationEventManager.isInitialized) {
            return
        }
        try {
            iviNavigationEventManager.onSuccess()
        } catch (error: RemoteException) {
            Log.e(TAG, "Unable to report assistance success", error)
        }
    }

    private fun notifyAssistanceMessage(message: String) {
        if (!::iviNavigationEventManager.isInitialized) {
            return
        }
        try {
            iviNavigationEventManager.setMessage(message)
        } catch (error: RemoteException) {
            Log.e(TAG, "Unable to report assistance message", error)
        }
    }

    override fun onLocationChanged(location: android.location.Location) {
        Log.d(TAG, "Location changed")
    }

    override fun onRequestRoute(latitude: Double, longitude: Double) {
        Log.d(TAG, "Route requested by coordinates")
        _destinationPoi.postValue(Point.fromLngLat(longitude, latitude))
    }

    override fun onRequestRoute(place: String) {
        Log.d(TAG, "onRequestRoute: $place")
        _destinationPlace.postValue(place)
    }

    override fun onSearchNearBy(category: String, limit: Int) {
        //_nearBy.postValue(Pair(category, limit))
        searchNearBy(category, limit)
    }

    override fun onMessage(message: String?) {
        Log.d(TAG, "onMessage: $message")
    }

    override fun onSuccess() {
        Log.d(TAG, "onSuccess")
    }

    override fun onError(errCode: Int, message: String?) {
        Log.d(TAG, "onError: $message")
    }

    override fun onCleared() {
        detailJob?.cancel()
        LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        if (::iviNavigationEventManager.isInitialized && assistanceListenerRegistered) {
            iviNavigationEventManager.unregisterNavigationEventListener(this)
            assistanceListenerRegistered = false
        }
        assistanceConnectionRequested = false
        disconnectCarService()
        super.onCleared()
    }
}
