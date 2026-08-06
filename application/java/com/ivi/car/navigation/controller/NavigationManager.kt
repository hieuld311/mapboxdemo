package com.ivi.car.navigation.controller

import android.content.Context
import com.ivi.car.navigation.model.NavigationResultCode
import com.ivi.car.navigation.model.NavigationCommandEvent
import com.ivi.car.navigation.model.NavigationCommandEventType
import com.ivi.car.navigation.model.NavigationState
import com.ivi.car.navigation.model.NavigationStatus
import com.ivi.car.navigation.model.NavigationSearchResult
import com.ivi.car.navigation.model.NavigationSuggestion
import com.ivi.car.navigation.model.HomeLocation
import com.ivi.car.navigation.model.NearbyCategory
import com.ivi.car.navigation.model.MapStyleMode
import com.ivi.car.navigation.model.NavigationDemoMode
import com.ivi.car.navigation.model.SuggestionSort
import com.ivi.car.navigation.model.WorkLocation
import com.ivi.car.navigation.repository.HomeRepository
import com.ivi.car.navigation.repository.NavigationSearchRepository
import com.ivi.car.navigation.repository.TripadvisorRepository
import com.ivi.car.navigation.repository.WorkRepository
import com.ivi.car.navigation.util.Constant
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.Bearing
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.core.MapboxNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object NavigationManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state
    private val _commandEvents = MutableSharedFlow<NavigationCommandEvent>(
        extraBufferCapacity = COMMAND_EVENT_BUFFER_SIZE
    )
    val commandEvents: SharedFlow<NavigationCommandEvent> = _commandEvents.asSharedFlow()

    private lateinit var applicationContext: Context
    private lateinit var homeRepository: HomeRepository
    private lateinit var workRepository: WorkRepository
    private lateinit var searchRepository: NavigationSearchRepository
    @Volatile
    private var mapboxNavigation: MapboxNavigation? = null
    @Volatile
    private var currentPoint: Point? = null
    private val suggestionsById = LinkedHashMap<String, NavigationSuggestion>()

    fun initialize(context: Context) {
        if (::applicationContext.isInitialized) return
        applicationContext = context.applicationContext
        homeRepository = HomeRepository(applicationContext)
        workRepository = WorkRepository(applicationContext)
        searchRepository = NavigationSearchRepository(
            TripadvisorRepository(applicationContext)
        )
        val savedStyle = applicationContext
            .getSharedPreferences(
                Constant.KEY_SHARED_PREFERENCES,
                Context.MODE_PRIVATE
            )
            .getInt(Constant.STYLE, MapStyleMode.NORMAL.code)
        val savedDemoMode = applicationContext
            .getSharedPreferences(
                Constant.KEY_SHARED_PREFERENCES,
                Context.MODE_PRIVATE
            )
            .getInt(Constant.DEMO_MODE, NavigationDemoMode.NORMAL.code)
        updateState {
            it.copy(
                home = homeRepository.getHome(),
                work = workRepository.getWork(),
                mapStyle = MapStyleMode.fromCode(savedStyle) ?: MapStyleMode.NORMAL,
                demoMode = NavigationDemoMode.fromCode(savedDemoMode) ?: NavigationDemoMode.NORMAL,
                message = null
            )
        }
    }

    fun attachNavigation(navigation: MapboxNavigation) {
        mapboxNavigation = navigation
        if (_state.value.status == NavigationStatus.UNAVAILABLE) {
            updateState { it.copy(status = NavigationStatus.IDLE, message = null) }
        }
    }

    fun updateCurrentPoint(point: Point?) {
        currentPoint = point
        if (point != null &&
            _state.value.status == NavigationStatus.UNAVAILABLE &&
            _state.value.destination == null
        ) {
            updateState {
                it.copy(
                    status = NavigationStatus.IDLE,
                    message = "Location is available"
                )
            }
        }
    }

    fun setRoute(destination: String): Int {
        if (destination.isBlank()) return NavigationResultCode.INVALID_ARGUMENT
        if (!isNavigationReady()) return setUnavailable("Navigation SDK is not ready")
        if (currentPoint == null) return setUnavailable(
            "Current location is unavailable",
            NavigationResultCode.LOCATION_UNAVAILABLE
        )
        updateState {
            it.copy(
                status = NavigationStatus.ROUTE_CALCULATING,
                suggestions = emptyList(),
                message = "Resolving destination: $destination"
            )
        }
        scope.launch {
            val resolved = runCatching {
                withContext(Dispatchers.IO) {
                    searchRepository.resolveDestination(destination)
                }
            }.getOrNull()
            if (resolved == null) {
                val message = "Destination was not found"
                setUnavailable(message, NavigationResultCode.NOT_FOUND)
                emitCommandError(COMMAND_SET_ROUTE, NavigationResultCode.NOT_FOUND, message)
                return@launch
            }
            requestRoute(
                originLocation = null,
                destination = resolved,
                completion = null,
                commandApi = COMMAND_SET_ROUTE
            )
        }
        return NavigationResultCode.ACCEPTED
    }

    suspend fun searchNearby(
        categoryCode: Int,
        limit: Int = DEFAULT_SEARCH_LIMIT,
        sortCode: Int? = null
    ): NavigationSearchResult {
        val category = NearbyCategory.fromCode(categoryCode)
            ?: return NavigationSearchResult(
                NavigationResultCode.INVALID_ARGUMENT,
                null,
                null,
                emptyList(),
                "Unsupported nearby category: $categoryCode"
            )
        val sortBy = when (sortCode) {
            null, SORT_UNSPECIFIED -> null
            else -> SuggestionSort.fromCode(sortCode)
                ?: return NavigationSearchResult(
                    NavigationResultCode.INVALID_ARGUMENT,
                    category,
                    null,
                    emptyList(),
                    "Unsupported sort option: $sortCode"
                )
        }
        val normalizedLimit = if (limit <= 0) DEFAULT_SEARCH_LIMIT else limit
        if (normalizedLimit !in 1..MAX_SEARCH_LIMIT) {
            return NavigationSearchResult(
                NavigationResultCode.INVALID_ARGUMENT,
                category,
                sortBy,
                emptyList(),
                "Limit must be between 1 and $MAX_SEARCH_LIMIT"
            )
        }
        val origin = currentPoint
            ?: return NavigationSearchResult(
                NavigationResultCode.LOCATION_UNAVAILABLE,
                category,
                sortBy,
                emptyList(),
                "Current location is unavailable"
            )

        val result = runCatching {
            searchRepository.searchNearby(category, normalizedLimit, sortBy, origin)
        }.getOrElse { error ->
            NavigationSearchResult(
                NavigationResultCode.INTERNAL_ERROR,
                category,
                sortBy,
                emptyList(),
                error.message ?: "Nearby search failed"
            )
        }
        publishSearchResult(result)
        return result
    }

    suspend fun searchDestinationSuggestions(
        query: String,
        limit: Int = DEFAULT_SEARCH_LIMIT
    ): NavigationSearchResult {
        if (query.isBlank()) {
            return NavigationSearchResult(
                NavigationResultCode.INVALID_ARGUMENT,
                null,
                null,
                emptyList(),
                "Destination query is empty"
            )
        }
        val normalizedLimit = if (limit <= 0) DEFAULT_SEARCH_LIMIT else limit
        if (normalizedLimit !in 1..MAX_SEARCH_LIMIT) {
            return NavigationSearchResult(
                NavigationResultCode.INVALID_ARGUMENT,
                null,
                null,
                emptyList(),
                "Limit must be between 1 and $MAX_SEARCH_LIMIT"
            )
        }
        val result = runCatching {
            searchRepository.searchDestinationSuggestions(
                query = query,
                limit = normalizedLimit,
                origin = currentPoint
            )
        }.getOrElse { error ->
            NavigationSearchResult(
                NavigationResultCode.INTERNAL_ERROR,
                null,
                null,
                emptyList(),
                error.message ?: "Destination search failed"
            )
        }
        publishSearchResult(result)
        return result
    }

    fun clearSearchResults() {
        synchronized(suggestionsById) {
            suggestionsById.clear()
        }
        updateState { state ->
            if (state.status == NavigationStatus.SHOWING_SUGGESTIONS ||
                (state.status == NavigationStatus.UNAVAILABLE && state.destination == null)
            ) {
                state.copy(
                    status = NavigationStatus.IDLE,
                    suggestions = emptyList(),
                    message = null
                )
            } else {
                state.copy(suggestions = emptyList())
            }
        }
    }

    fun selectSuggestion(suggestionId: String): Int {
        if (suggestionId.isBlank()) return NavigationResultCode.INVALID_ARGUMENT
        val suggestion = synchronized(suggestionsById) {
            if (suggestionsById.isEmpty()) {
                return NavigationResultCode.INVALID_STATE
            }
            suggestionsById[suggestionId]?.also {
                suggestionsById.clear()
            }
        } ?: return NavigationResultCode.NOT_FOUND
        if (searchRepository.hasAutocompleteSuggestion(suggestionId)) {
            if (!isNavigationReady()) return setUnavailable("Navigation SDK is not ready")
            if (currentPoint == null) {
                return setUnavailable(
                    "Current location is unavailable",
                    NavigationResultCode.LOCATION_UNAVAILABLE
                )
            }
            updateState {
                it.copy(
                    status = NavigationStatus.ROUTE_CALCULATING,
                    destination = suggestion,
                    suggestions = emptyList(),
                    message = "Resolving ${suggestion.name}"
                )
            }
            scope.launch {
                val confirmedPoint = searchRepository.confirmAutocompleteSuggestion(suggestionId)
                if (confirmedPoint == null) {
                    val message = "Destination could not be resolved"
                    setUnavailable(message, NavigationResultCode.NOT_FOUND)
                    emitCommandError(COMMAND_SELECT_SUGGESTION, NavigationResultCode.NOT_FOUND, message)
                    return@launch
                }
                requestRoute(
                    originLocation = null,
                    destination = suggestion.copy(point = confirmedPoint),
                    completion = null,
                    commandApi = COMMAND_SELECT_SUGGESTION
                )
            }
            return NavigationResultCode.ACCEPTED
        }
        return requestRoute(
            originLocation = null,
            destination = suggestion,
            completion = null,
            commandApi = COMMAND_SELECT_SUGGESTION
        )
    }

    /**
     * UI-only detail enrichment. It deliberately does not consume the suggestion or change
     * navigation state; Directions remains the action that calls selectSuggestion().
     */
    suspend fun loadSuggestionDetails(suggestionId: String): NavigationSuggestion? {
        val suggestion = synchronized(suggestionsById) {
            suggestionsById[suggestionId]
        } ?: return null
        return runCatching {
            searchRepository.loadSuggestionDetails(suggestion)
        }.getOrNull()
    }

    fun findRoute(
        originLocation: Location?,
        destinationPoint: Point,
        destinationName: String = "Selected destination",
        completion: ((Boolean, String?) -> Unit)? = null
    ): Int {
        val destination = NavigationSuggestion(
            suggestionId = coordinateId(destinationPoint),
            name = destinationName,
            address = null,
            point = destinationPoint,
            category = null,
            distanceMeters = null,
            rating = null,
            reviewCount = null,
            ratingSource = null,
            photoUrl = null,
            detailsUrl = null,
            source = "MAPBOX"
        )
        return requestRoute(originLocation, destination, completion)
    }

    fun startNavigatingHome(): Int {
        val home = homeRepository.getHome()
            ?: return NavigationResultCode.HOME_NOT_CONFIGURED
        val destination = NavigationSuggestion(
            suggestionId = coordinateId(home.point),
            name = home.name,
            address = home.address,
            point = home.point,
            category = null,
            distanceMeters = null,
            rating = null,
            reviewCount = null,
            ratingSource = null,
            photoUrl = null,
            detailsUrl = null,
            source = "HOME"
        )
        return requestRoute(null, destination, null, COMMAND_START_NAVIGATING_HOME)
    }

    fun startNavigatingWork(): Int {
        val work = workRepository.getWork()
            ?: return NavigationResultCode.WORK_NOT_CONFIGURED
        val destination = NavigationSuggestion(
            suggestionId = coordinateId(work.point),
            name = work.name,
            address = work.address,
            point = work.point,
            category = null,
            distanceMeters = null,
            rating = null,
            reviewCount = null,
            ratingSource = null,
            photoUrl = null,
            detailsUrl = null,
            source = "WORK"
        )
        return requestRoute(null, destination, null)
    }

    fun setHome(name: String, address: String?, point: Point) {
        val home = HomeLocation(
            name = name.ifBlank { "Home" },
            address = address,
            point = point,
            updatedAt = System.currentTimeMillis()
        )
        homeRepository.saveHome(home)
        updateState { it.copy(home = home, message = "Home updated") }
    }

    fun clearHome() {
        homeRepository.clearHome()
        updateState { it.copy(home = null, message = "Home removed") }
    }

    fun setWork(name: String, address: String?, point: Point) {
        val work = WorkLocation(
            name = name.ifBlank { "Work" },
            address = address,
            point = point,
            updatedAt = System.currentTimeMillis()
        )
        workRepository.saveWork(work)
        updateState { it.copy(work = work, message = "Work updated") }
    }

    fun clearWork() {
        workRepository.clearWork()
        updateState { it.copy(work = null, message = "Work removed") }
    }

    fun setMapStyle(styleCode: Int): Int {
        val style = MapStyleMode.fromCode(styleCode)
            ?: return NavigationResultCode.INVALID_ARGUMENT
        applicationContext
            .getSharedPreferences(
                Constant.KEY_SHARED_PREFERENCES,
                Context.MODE_PRIVATE
            )
            .edit()
            .putInt(Constant.STYLE, style.code)
            .apply()
        updateState {
            it.copy(
                mapStyle = style,
                message = "Map style changed to ${style.name}"
            )
        }
        return NavigationResultCode.ACCEPTED
    }

    fun setNavigationDemoMode(modeCode: Int): Int {
        val demoMode = NavigationDemoMode.fromCode(modeCode)
            ?: return NavigationResultCode.INVALID_ARGUMENT
        applicationContext
            .getSharedPreferences(
                Constant.KEY_SHARED_PREFERENCES,
                Context.MODE_PRIVATE
            )
            .edit()
            .putInt(Constant.DEMO_MODE, demoMode.code)
            .apply()
        updateState {
            it.copy(
                demoMode = demoMode,
                message = "Demo mode changed to ${demoMode.name}"
            )
        }
        return NavigationResultCode.ACCEPTED
    }

    fun markSimulationStarted() {
        updateState {
            it.copy(
                status = NavigationStatus.SIMULATING_DRIVE,
                message = "Simulating drive"
            )
        }
    }

    fun updateProgress(distanceRemainingMeters: Double, durationRemainingSeconds: Int) {
        updateState {
            it.copy(
                status = NavigationStatus.SIMULATING_DRIVE,
                distanceRemainingMeters = distanceRemainingMeters,
                durationRemainingSeconds = durationRemainingSeconds
            )
        }
    }

    fun stopNavigation() {
        mapboxNavigation?.setNavigationRoutes(emptyList())
        synchronized(suggestionsById) {
            suggestionsById.clear()
        }
        updateState {
            NavigationState(
                status = NavigationStatus.IDLE,
                home = homeRepository.getHome(),
                work = workRepository.getWork(),
                mapStyle = it.mapStyle,
                demoMode = it.demoMode,
                version = it.version
            )
        }
    }

    fun getStateJson(): String = _state.value.toJson()

    fun getHome(): HomeLocation? = homeRepository.getHome()

    fun getWork(): WorkLocation? = workRepository.getWork()

    private fun requestRoute(
        originLocation: Location?,
        destination: NavigationSuggestion,
        completion: ((Boolean, String?) -> Unit)?,
        commandApi: String? = null
    ): Int {
        val navigation = mapboxNavigation
            ?: return setUnavailable("Navigation SDK is not ready")
        val origin = originLocation?.let {
            Point.fromLngLat(it.longitude, it.latitude)
        } ?: currentPoint
            ?: return setUnavailable(
                "Current location is unavailable",
                NavigationResultCode.LOCATION_UNAVAILABLE
            )

        updateState {
            it.copy(
                status = NavigationStatus.ROUTE_CALCULATING,
                destination = destination,
                suggestions = emptyList(),
                message = "Calculating route to ${destination.name}"
            )
        }
        val routeOptionsBuilder = RouteOptions.builder()
            .applyDefaultNavigationOptions()
            .applyLanguageAndVoiceUnitOptions(applicationContext)
            .coordinatesList(listOf(origin, destination.point))
            .language("en-us")
            .voiceInstructions(true)
            .voiceUnits(DirectionsCriteria.METRIC)
            .layersList(listOf(navigation.getZLevel(), null))
        if (originLocation != null) {
            routeOptionsBuilder.bearingsList(
                listOf(
                    originLocation.bearing?.let {
                        Bearing.builder()
                            .angle(it.toDouble())
                            .degrees(60.0)
                            .build()
                    },
                    null
                )
            )
        }
        navigation.requestRoutes(
            routeOptionsBuilder.build(),
            object : NavigationRouterCallback {
                override fun onCanceled(routeOptions: RouteOptions, routerOrigin: String) {
                    val message = "Route request was canceled"
                    setUnavailable(message, NavigationResultCode.INTERNAL_ERROR)
                    commandApi?.let { emitCommandError(it, NavigationResultCode.INTERNAL_ERROR, message) }
                    completion?.invoke(false, message)
                }

                override fun onFailure(
                    reasons: List<RouterFailure>,
                    routeOptions: RouteOptions
                ) {
                    val message = reasons.firstOrNull()?.toString() ?: "Route request failed"
                    setUnavailable(message, NavigationResultCode.INTERNAL_ERROR)
                    commandApi?.let { emitCommandError(it, NavigationResultCode.INTERNAL_ERROR, message) }
                    completion?.invoke(false, message)
                }

                override fun onRoutesReady(
                    routes: List<NavigationRoute>,
                    routerOrigin: String
                ) {
                    if (routes.isEmpty()) {
                        val message = "No routes are available"
                        setUnavailable(message, NavigationResultCode.NOT_FOUND)
                        commandApi?.let { emitCommandError(it, NavigationResultCode.NOT_FOUND, message) }
                        completion?.invoke(false, message)
                        return
                    }
                    navigation.setNavigationRoutes(routes)
                    updateState {
                        it.copy(
                            status = NavigationStatus.ROUTE_SET,
                            destination = destination,
                            message = "Route is ready"
                        )
                    }
                    commandApi?.let { emitCommandSuccess(it, destination) }
                    completion?.invoke(true, null)
                }
            }
        )
        return NavigationResultCode.ACCEPTED
    }

    private fun isNavigationReady(): Boolean = mapboxNavigation != null

    private fun emitCommandSuccess(api: String, destination: NavigationSuggestion) {
        _commandEvents.tryEmit(
            NavigationCommandEvent(
                type = NavigationCommandEventType.SUCCESS,
                api = api,
                resultCode = NavigationResultCode.ACCEPTED,
                message = "Route is ready",
                destination = destination
            )
        )
    }

    private fun emitCommandError(api: String, resultCode: Int, message: String) {
        _commandEvents.tryEmit(
            NavigationCommandEvent(
                type = NavigationCommandEventType.ERROR,
                api = api,
                resultCode = resultCode,
                message = message
            )
        )
    }

    private fun publishSearchResult(result: NavigationSearchResult) {
        synchronized(suggestionsById) {
            suggestionsById.clear()
            result.candidates.forEach { suggestionsById[it.suggestionId] = it }
        }
        updateState {
            it.copy(
                status = when (result.resultCode) {
                    NavigationResultCode.ACCEPTED -> NavigationStatus.SHOWING_SUGGESTIONS
                    NavigationResultCode.NOT_FOUND -> NavigationStatus.IDLE
                    else -> NavigationStatus.UNAVAILABLE
                },
                suggestions = result.candidates,
                message = result.message
            )
        }
    }

    private fun setUnavailable(
        message: String,
        resultCode: Int = NavigationResultCode.UNAVAILABLE
    ): Int {
        updateState {
            it.copy(
                status = NavigationStatus.UNAVAILABLE,
                message = message
            )
        }
        return resultCode
    }

    private fun updateState(transform: (NavigationState) -> NavigationState) {
        synchronized(_state) {
            val previous = _state.value
            _state.value = transform(previous).copy(version = previous.version + 1)
        }
    }

    private fun coordinateId(point: Point): String {
        return "${point.longitude()},${point.latitude()}"
    }

    private const val DEFAULT_SEARCH_LIMIT = 5
    private const val MAX_SEARCH_LIMIT = 20
    private const val SORT_UNSPECIFIED = 0
    private const val COMMAND_EVENT_BUFFER_SIZE = 16
    private const val COMMAND_SET_ROUTE = "setRoute"
    private const val COMMAND_SELECT_SUGGESTION = "selectSuggestion"
    private const val COMMAND_START_NAVIGATING_HOME = "startNavigatingHome"
}
