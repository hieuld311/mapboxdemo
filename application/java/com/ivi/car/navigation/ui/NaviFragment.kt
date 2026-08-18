package com.ivi.car.navigation.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Resources
import android.location.Location
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.car.ivi.mocklocation.ILocationUpdate
import com.ivi.car.navigation.NaviAidlInterface
import com.ivi.car.navigation.R
import com.ivi.car.navigation.controller.NavigationManager
import com.ivi.car.navigation.databinding.FragmentMainBinding
import com.ivi.car.navigation.model.NavigationResultCode
import com.ivi.car.navigation.model.NavigationStatus
import com.ivi.car.navigation.model.NavigationSuggestion
import com.ivi.car.navigation.model.NearbyCategory
import com.ivi.car.navigation.model.MapStyleMode
import com.ivi.car.navigation.model.Navigation
import com.ivi.car.navigation.service.NaviAidlService
import com.ivi.car.navigation.util.Constant
import com.ivi.car.navigation.util.SharePreferences
import com.ivi.car.navigation.util.Utils
import com.ivi.car.navigation.viewmodel.AudioViewModel
import com.ivi.car.navigation.viewmodel.NaviViewModel
import com.mapbox.android.gestures.MoveGestureDetector
import com.mapbox.bindgen.Value
import com.mapbox.common.location.toAndroidLocation
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.properties.generated.IconAnchor
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.gestures.OnMoveListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.LocationComponentConstants
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.logo.logo
import com.mapbox.maps.plugin.scalebar.scalebar
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.base.TimeFormat
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.base.trip.model.RouteProgressState
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.lifecycle.requireMapboxNavigation
import com.mapbox.navigation.core.replay.route.ReplayProgressObserver
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.VoiceInstructionsObserver
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import com.mapbox.navigation.tripdata.progress.api.MapboxTripProgressApi
import com.mapbox.navigation.tripdata.progress.model.DistanceRemainingFormatter
import com.mapbox.navigation.tripdata.progress.model.EstimatedTimeToArrivalFormatter
import com.mapbox.navigation.tripdata.progress.model.PercentDistanceTraveledFormatter
import com.mapbox.navigation.tripdata.progress.model.TimeRemainingFormatter
import com.mapbox.navigation.tripdata.progress.model.TripProgressUpdateFormatter
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverPrimaryOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverSecondaryOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverSubOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverViewOptions
import com.mapbox.navigation.ui.components.tripprogress.model.TripProgressViewOptions
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowApi
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowView
import com.mapbox.navigation.ui.maps.route.arrow.model.RouteArrowOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import com.mapbox.navigation.utils.internal.toPoint
import dagger.hilt.android.AndroidEntryPoint
import fauto.car.FAutoCar
import fauto.car.sharedata.FAutoShareDataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt


@AndroidEntryPoint
class NaviFragment : Fragment() {
    private val TAG = this.javaClass.name

    private enum class SearchPanelMode { HIDDEN, SUGGESTIONS, DETAIL }

    private enum class SavedPlace(val label: String) { HOME("Home"), WORK("Work") }

    private var _binding: FragmentMainBinding? = null
    private val binding: FragmentMainBinding
        get() = requireNotNull(_binding) { "Fragment view is not available" }
    private val naviViewModel: NaviViewModel by activityViewModels()
    private val audioViewModel: AudioViewModel by activityViewModels()
    private lateinit var sharedPreferences: SharedPreferences

    private var naviAIDL: NaviAidlInterface? = null
    private var naviAidlBound = false

    private val aidlConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            naviAIDL = NaviAidlInterface.Stub.asInterface(service)
            naviAidlBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            naviAIDL = null
        }
    }

    private var mFAutoCar: FAutoCar? = null
    private var mFAutoShareDataManager: FAutoShareDataManager? = null
    private var fAutoCarConnected = false

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        @RequiresPermission("fauto.car.permission.CONTROL_FSHARESERVICE")
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            mFAutoShareDataManager = mFAutoCar
                ?.getFAutoCarManager(FAutoShareDataManager.SHARE_DATA_SERVICE)
                ?.let { manager -> manager as? FAutoShareDataManager }
            fAutoCarConnected = true

            Log.i(TAG,"mFAutoShareDataManager != null : ${mFAutoShareDataManager!= null}")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            fAutoCarConnected = false
            mFAutoCar = null
            mFAutoShareDataManager = null
        }
    }

    private lateinit var replayProgressObserver: ReplayProgressObserver

    private lateinit var navigationCamera: NavigationCamera

    private lateinit var viewportDataSource: MapboxNavigationViewportDataSource

    private var navigation: Navigation = Navigation()
    private var routeTotalDistanceMeters: Double? = null
    private var appliedMapStyle: MapStyleMode? = null
    private var lastObservedStatus: NavigationStatus? = null

    private var jobResetToFlowing: Job? = null
    private var mockLocationUpdateJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var photoLoadJob: Job? = null
    private var mainSelectedSuggestionId: String? = null
    private val pixelDensity = Resources.getSystem().displayMetrics.density
    private val overviewPadding: EdgeInsets by lazy {
        EdgeInsets(
            200.0 * pixelDensity, 40.0 * pixelDensity, 150.0 * pixelDensity, 40.0 * pixelDensity
        )
    }
    private val landscapeOverviewPadding: EdgeInsets by lazy {
        EdgeInsets(
            0.0 * pixelDensity, 30.0 * pixelDensity, 30.0 * pixelDensity, 30.0 * pixelDensity
        )
    }
    private val landscapeFollowingPadding: EdgeInsets by lazy {
        EdgeInsets(
            110.0 * pixelDensity, 0.0 * pixelDensity, 30.0 * pixelDensity, 0.0 * pixelDensity
        )
    }

    private lateinit var tripProgressApi: MapboxTripProgressApi

    private lateinit var routeLineApi: MapboxRouteLineApi

    private lateinit var routeLineView: MapboxRouteLineView

    private val routeArrowApi: MapboxRouteArrowApi = MapboxRouteArrowApi()

    private lateinit var routeArrowView: MapboxRouteArrowView

    private var isVoiceInstructionsMuted = true
        set(value) {
            field = value
            if (value) {
                binding.soundButton.muteAndExtend(BUTTON_ANIMATION_DURATION)
            } else {
                binding.soundButton.unmuteAndExtend(BUTTON_ANIMATION_DURATION)
            }
        }

    @Inject
    lateinit var maneuverApi: MapboxManeuverApi

    @Inject
    lateinit var distanceFormatterOptions: DistanceFormatterOptions

    private val voiceInstructionsObserver = VoiceInstructionsObserver { voiceInstructions ->
        if (!audioViewModel.isVoiceInstructionsMuted) {
            voiceInstructions.ssmlAnnouncement()
                ?.let { audioViewModel.requestVoiceInstructionsWithSsml(it) }
        }
    }

    private val navigationLocationProvider = NavigationLocationProvider()

    private val locationObserver = object : LocationObserver {
        var firstLocationUpdateReceived = false

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            val enhancedLocation = locationMatcherResult.enhancedLocation
            // update location puck's position on the map
            navigationLocationProvider.changePosition(
                location = enhancedLocation,
                keyPoints = locationMatcherResult.keyPoints,
            )

            // update camera position to account for new location
            viewportDataSource.onLocationChanged(enhancedLocation)
            viewportDataSource.evaluate()

            // Mirror puck position + current map camera to the launcher's live map widget (see
            // NavigationManager.widgetLocationMatcherResult/widgetCamera, consumed by
            // MapWidgetSurfaceService). This runs continuously (trip session is always active,
            // see onAttached below) so the widget shows map + puck even when idle, matching
            // NaviFragment's own puck/camera exactly - real GPS or simulated replay alike.
            NavigationManager.updateWidgetLocationMatcherResult(locationMatcherResult)
            val widgetCameraState = binding.mapView.mapboxMap.cameraState
            NavigationManager.updateWidgetCamera(
                NavigationManager.WidgetCameraSnapshot(
                    center = widgetCameraState.center,
                    zoom = widgetCameraState.zoom,
                    bearing = widgetCameraState.bearing,
                    pitch = widgetCameraState.pitch
                )
            )
            //update location when simulate
            if (isLocationConnected) {
                mockLocationUpdateJob?.cancel()
                mockLocationUpdateJob = lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        serviceInterface.updateMockLocation(
                            "${enhancedLocation.latitude},${enhancedLocation.longitude}"
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "Unable to update mock location", error)
                    }
                }
            }
            naviViewModel.currentPoint = enhancedLocation.toPoint()
            // if this is the first location update the activity has received,
            // it's best to immediately move the camera to the current user location
            if (!firstLocationUpdateReceived) {
                firstLocationUpdateReceived = true
                updateCamera(enhancedLocation.toAndroidLocation())
            }
        }

        override fun onNewRawLocation(rawLocation: com.mapbox.common.location.Location) {

        }
    }

    private val onPositionChangedListener = OnIndicatorPositionChangedListener { point ->
        val result = routeLineApi.updateTraveledRouteLine(point)
        binding.mapView.mapboxMap.style?.apply {
            routeLineView.renderRouteLineUpdate(this, result)
        }
    }

    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        // update the camera position to account for the progressed fragment of the route
        resetToFlowing()
        routeLineApi.updateWithRouteProgress(routeProgress) { result ->
            binding.mapView.mapboxMap.style?.apply {
                routeLineView.renderRouteLineUpdate(this, result)
            }
        }
        // draw the upcoming maneuver arrow on the map
        val style = binding.mapView.mapboxMap.style
        if (style != null) {
            val maneuverArrowResult = routeArrowApi.addUpcomingManeuverArrow(routeProgress)
            routeArrowView.renderManeuverUpdate(style, maneuverArrowResult)
        }

        var stepRoad: String? = null
        var stepDistanceRemaining: Double? = null
        var maneuverType: String? = null
        var maneuverModifier: String? = null
        val maneuvers = maneuverApi.getManeuvers(routeProgress)
        binding.maneuverView.renderManeuvers(maneuvers)
        maneuvers.fold({ error ->
            Toast.makeText(
                requireContext(), error.errorMessage, Toast.LENGTH_SHORT
            ).show()
            error
        }, { maneuverList ->
            maneuverList.firstOrNull()?.let { firstManeuver ->
                stepRoad = firstManeuver.primary.text
                stepDistanceRemaining = firstManeuver.stepDistance.distanceRemaining
                maneuverType = firstManeuver.primary.type?.toString()
                maneuverModifier = firstManeuver.primary.modifier?.toString()
            }
            maneuverList
        })
        Utils.updateNavigation(
            navigation = navigation,
            distanceRemaining = routeProgress.distanceRemaining.toDouble(),
            durationRemaining = routeProgress.durationRemaining.toInt(),
            stepRoad = stepRoad,
            stepDistanceRemaining = stepDistanceRemaining,
            maneuverType = maneuverType,
            maneuverModifier = maneuverModifier,
            fractionTraveled = routeProgress.fractionTraveled.toDouble()
        )
        NavigationManager.updateProgress(
            routeProgress.distanceRemaining.toDouble(),
            routeProgress.durationRemaining.toInt()
        )
        // Drives the launcher widget's maneuver+trip-progress card (see
        // MapWidgetSurfaceService.progressJob) - non-null here means "route active", which is
        // exactly the card's visibility condition.
        NavigationManager.updateWidgetRouteProgress(routeProgress)
        sendNaviData()

        // update bottom trip progress summary
        binding.tripProgressView.render(
            tripProgressApi.getTripProgress(routeProgress)
        )
        updateBottomNavigationCard(routeProgress)
        viewportDataSource.onRouteProgressChanged(routeProgress)
        viewportDataSource.evaluate()

        if (routeProgress.distanceRemaining.toInt() == 0 || routeProgress.currentState == RouteProgressState.COMPLETE) {
            stopSimulationInvoke()
        }
        updateVehiclePuck()
    }

    private fun connectToCarService() {
        if (fAutoCarConnected || mFAutoCar != null) {
            return
        }
        Log.i(TAG,"connectToCarService")
        mFAutoCar = FAutoCar.createFAutoCar(context?.applicationContext, serviceConnection)
        mFAutoCar?.connect()
    }

    @RequiresPermission("fauto.car.permission.CONTROL_FSHARESERVICE")
    private fun sendNaviData() {
        val normalizeNavi = Utils.normalizeData(navigation)
        naviAIDL?.sendNaviData(normalizeNavi.toString())
        naviViewModel.sendNavDataToSomeIp(normalizeNavi)
        Log.i(TAG, "sendNaviData")
        val navData = Utils.convertToJsonData(normalizeNavi)
        mFAutoShareDataManager?.onNaviDataReceived(navData)
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG,"onPause()")
    }

    override fun onStop() {
        Log.i(TAG,"onStop()")
        disconnectForegroundConnections()
        naviViewModel.disconnectCarService()
        super.onStop()
    }

    private fun stopSimulationInvoke() {
        routeTotalDistanceMeters = null
        naviViewModel.clearNavigationRoutes()
        navigation = Navigation()
        sendNaviData()
        naviViewModel.stopSimulation()
        viewportDataSource.clearRouteData()
        viewportDataSource.evaluate()
        hideUiElement()
        (activity as? MainActivity)?.stopNavigationService()
    }

    private fun updateVehiclePuck() {
        binding.mapView.location.apply {
            locationPuck = createDefault2DPuck(true).apply {
                topImage = ImageHolder.from(com.mapbox.maps.R.drawable.mapbox_user_puck_icon)
                bearingImage = ImageHolder.from(com.mapbox.maps.R.drawable.mapbox_user_puck_icon)
                shadowImage =
                    ImageHolder.Companion.from(com.mapbox.maps.R.drawable.mapbox_user_icon_shadow)
            }
        }
    }

    private val routesObserver = RoutesObserver { routeUpdateResult ->
        // Mirror the raw route list to the widget regardless of branch below, so its route line
        // clears the moment routes go empty instead of lagging behind the else-branch's own map.
        NavigationManager.updateWidgetRoutes(routeUpdateResult.navigationRoutes)
        if (routeUpdateResult.navigationRoutes.isNotEmpty()) {
            routeTotalDistanceMeters = null
            (activity as? MainActivity)?.ensureNavigationServiceRunning()
            val primaryRoute = routeUpdateResult.navigationRoutes.first()
            routeLineApi.setNavigationRoutes(
                routeUpdateResult.navigationRoutes
            ) { value ->
                binding.mapView.mapboxMap.style?.apply {
                    routeLineView.renderRouteDrawData(this, value)
                }
            }
            viewportDataSource.onRouteChanged(primaryRoute)
            viewportDataSource.evaluate()
            navigationCamera.requestNavigationCameraToOverview {
                binding.soundButton.visibility = View.VISIBLE
                //binding.routeOverview.visibility = View.VISIBLE
                binding.maneuverCard.visibility = View.VISIBLE
                binding.tripProgressCard.visibility = View.VISIBLE
            }
        } else {
            // remove the route line and route arrow from the map
            val style = binding.mapView.mapboxMap.style
            if (style != null) {
                routeLineApi.clearRouteLine { value ->
                    routeLineView.renderClearRouteLineValue(
                        style, value
                    )
                }
                routeArrowView.render(style, routeArrowApi.clearArrows())
            }

            // remove the route reference from camera position evaluations
            viewportDataSource.clearRouteData()
            viewportDataSource.evaluate()

            // Route gone - widget's maneuver/trip-progress card should hide (idle = map + puck
            // only, see MapWidgetSurfaceService.progressJob). Do NOT clear
            // widgetLocationMatcherResult/widgetCamera here: the puck and map position should
            // stay exactly as they are at idle, not reset.
            NavigationManager.updateWidgetRouteProgress(null)
        }
    }

    @OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
    private val mapboxNavigation: MapboxNavigation by requireMapboxNavigation(onResumedObserver = object :
        MapboxNavigationObserver {
        @SuppressLint("MissingPermission")
        override fun onAttached(mapboxNavigation: MapboxNavigation) {
            Log.i(TAG,"onAttached")
            (activity as? MainActivity)?.ensureNavigationServiceRunning()
            mapboxNavigation.registerRoutesObserver(routesObserver)
            mapboxNavigation.registerLocationObserver(locationObserver)
            mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
            mapboxNavigation.registerVoiceInstructionsObserver(voiceInstructionsObserver)
            replayProgressObserver = ReplayProgressObserver(mapboxNavigation.mapboxReplayer)
            mapboxNavigation.registerRouteProgressObserver(replayProgressObserver)
            mapboxNavigation.startTripSession()
        }

        override fun onDetached(mapboxNavigation: MapboxNavigation) {
            Log.i(TAG,"onDetached")
            mapboxNavigation.unregisterRoutesObserver(routesObserver)
            mapboxNavigation.unregisterLocationObserver(locationObserver)
            mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
            mapboxNavigation.unregisterRouteProgressObserver(replayProgressObserver)
            mapboxNavigation.unregisterVoiceInstructionsObserver(voiceInstructionsObserver)
        }
    }, onInitialize = { naviViewModel.initNavigation() })

    private lateinit var mapMarkersManager: MapMarkersManager

    private lateinit var serviceInterface: ILocationUpdate
    private var isLocationConnected = false
    private var mockLocationBound = false

    private var isSimulation = false

    private val mServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, iBinder: IBinder) {
            serviceInterface = ILocationUpdate.Stub.asInterface(iBinder)
            isLocationConnected = true
            mockLocationBound = true
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            isLocationConnected = false
        }
    }

    private fun bindToMockLocationService() {
        val intent = Intent(BIND_ACTION_NAME)
        intent.setPackage(PACKAGE_NAME)
        mockLocationBound =
            activity?.bindService(intent, mServiceConnection, AppCompatActivity.BIND_AUTO_CREATE)
                ?: false
    }

    private fun bindToAidlService() {
        val intent = Intent(requireContext(), NaviAidlService::class.java)
        naviAidlBound =
            activity?.bindService(intent, aidlConnection, Context.BIND_AUTO_CREATE) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        naviViewModel.setMapBoxNavigation(mapboxNavigation)
        viewportDataSource = MapboxNavigationViewportDataSource(binding.mapView.mapboxMap)
        navigationCamera = NavigationCamera(
            binding.mapView.mapboxMap, binding.mapView.camera, viewportDataSource
        )
        tripProgressApi = MapboxTripProgressApi(
            TripProgressUpdateFormatter.Builder(requireContext()).distanceRemainingFormatter(
                DistanceRemainingFormatter(distanceFormatterOptions)
            ).timeRemainingFormatter(
                TimeRemainingFormatter(requireContext())
            ).percentRouteTraveledFormatter(
                PercentDistanceTraveledFormatter()
            ).estimatedTimeToArrivalFormatter(
                EstimatedTimeToArrivalFormatter(requireContext(), TimeFormat.NONE_SPECIFIED)
            ).build()
        )
        val mapboxRouteApiOptions =
            MapboxRouteLineApiOptions.Builder().vanishingRouteLineEnabled(true).build()
        routeLineApi = MapboxRouteLineApi(mapboxRouteApiOptions)
        val mapboxRouteLineOptions = MapboxRouteLineViewOptions.Builder(requireContext()).routeLineBelowLayerId(LocationComponentConstants.LOCATION_INDICATOR_LAYER).build()
        routeLineView = MapboxRouteLineView(mapboxRouteLineOptions)
        val routeArrowOptions = RouteArrowOptions.Builder(requireContext()).build()
        routeArrowView = MapboxRouteArrowView(routeArrowOptions)
        mapMarkersManager = MapMarkersManager(binding.mapView, requireContext())
        sharedPreferences = SharePreferences.getPrefs(requireContext())
        isVoiceInstructionsMuted = audioViewModel.isVoiceInstructionsMuted
        initView()
        initAction()
        initObserver()
    }

    override fun onStart() {
        super.onStart()
        if (!mockLocationBound) {
            bindToMockLocationService()
        }
        if (!naviAidlBound) {
            bindToAidlService()
        }
        connectToCarService()
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG,"onResume")
        viewportDataSource.overviewPadding = landscapeOverviewPadding
        viewportDataSource.followingPadding = landscapeFollowingPadding
        viewportDataSource.evaluate()
        naviViewModel.connectService()
        audioViewModel.connectService()
    }

    private fun initView() {
        configureCompactNavigationComponents()
        val styleMode = NavigationManager.state.value.mapStyle
        applyMapStyle(styleMode, persist = false)
        binding.mapView.location.apply {
            setLocationProvider(navigationLocationProvider)
            addOnIndicatorPositionChangedListener(onPositionChangedListener)
            puckBearingEnabled = true
            showAccuracyRing = true
            enabled = true
            puckBearing = PuckBearing.COURSE
            locationPuck = createDefault2DPuck(true).apply {
                topImage = ImageHolder.from(com.mapbox.maps.R.drawable.mapbox_user_puck_icon)
                bearingImage = ImageHolder.from(com.mapbox.maps.R.drawable.mapbox_user_puck_icon)
                shadowImage = ImageHolder.Companion.from(com.mapbox.maps.R.drawable.mapbox_user_icon_shadow)
            }
        }
        binding.mapView.logo.enabled = false
        binding.mapView.attribution.enabled = false
        binding.mapView.scalebar.enabled = false
        binding.mapView.compass.enabled = false

    }

    private fun initAction() {
        binding.mapView.camera.addCameraAnimationsLifecycleListener(
            NavigationBasicGesturesHandler(navigationCamera)
        )
        binding.mapView.gestures.addOnMapLongClickListener { point ->
            naviViewModel.findRoute(
                navigationLocationProvider.lastLocation, point
            )
            true
        }

        binding.stop.setOnClickListener {
            stopSimulationInvoke()
            navigationCamera.requestNavigationCameraToIdle()
            //naviViewModel.sendNav()
        }

        binding.recenter.setOnClickListener {
            isSimulation = true
            navigationCamera.requestNavigationCameraToFollowing()
            binding.routeOverview.showTextAndExtend(BUTTON_ANIMATION_DURATION)
        }

        binding.soundButton.setOnClickListener {
            audioViewModel.setVoiceInstructionsMuted()
            isVoiceInstructionsMuted = audioViewModel.isVoiceInstructionsMuted
        }

        binding.changeStyle.setOnClickListener {
            showPopupWindow(it)
        }

        configureMainSearch()
        binding.categoryRestaurantChip.setOnClickListener {
            searchNearbyFromMain(NearbyCategory.RESTAURANT)
        }
        binding.categoryHotelChip.setOnClickListener {
            searchNearbyFromMain(NearbyCategory.HOTEL)
        }
        binding.categoryHospitalChip.setOnClickListener {
            searchNearbyFromMain(NearbyCategory.HOSPITAL)
        }
        binding.categoryGasChip.setOnClickListener {
            searchNearbyFromMain(NearbyCategory.GAS_STATION)
        }
        binding.categoryConvenienceChip.setOnClickListener {
            searchNearbyFromMain(NearbyCategory.CONVENIENCE_STORE)
        }

        binding.zoomIn.setOnClickListener {
            val zoom = (binding.mapView.mapboxMap.cameraState.zoom + 1.0).coerceAtMost(22.0)
            binding.mapView.mapboxMap.setCamera(CameraOptions.Builder().zoom(zoom).build())
        }

        binding.zoomOut.setOnClickListener {
            val zoom = (binding.mapView.mapboxMap.cameraState.zoom - 1.0).coerceAtLeast(0.0)
            binding.mapView.mapboxMap.setCamera(CameraOptions.Builder().zoom(zoom).build())
        }

//        binding.mapRecenter.setOnClickListener {
//            if (navigationLocationProvider.lastLocation != null) {
//                isSimulation = true
//                navigationCamera.requestNavigationCameraToFollowing()
//            } else {
//                naviViewModel.currentPoint?.let { point ->
//                    binding.mapView.mapboxMap.setCamera(
//                        CameraOptions.Builder()
//                            .center(point)
//                            .zoom(15.0)
//                            .build()
//                    )
//                }
//            }
//        }

        mapMarkersManager.onSuggestionClickListener = { result ->
            showMainPlaceDetail(result)
        }
        binding.mapView.gestures.addOnMoveListener(onMoveListener)
    }

    private val onMoveListener = object : OnMoveListener {
        override fun onMoveBegin(detector: MoveGestureDetector) {
            //naviViewModel.isScreenshot = true
            isSimulation = false
        }

        override fun onMove(detector: MoveGestureDetector): Boolean {
            isSimulation = false
            return false
        }

        override fun onMoveEnd(detector: MoveGestureDetector) {
            // Remain in free-pan mode until the user explicitly taps recenter.
        }
    }

    private fun initObserver() {
        navigationCamera.registerNavigationCameraStateChangeObserver { navigationCameraState ->
            when (navigationCameraState) {
                NavigationCameraState.TRANSITION_TO_FOLLOWING, NavigationCameraState.FOLLOWING -> binding.recenter.visibility =
                    View.INVISIBLE

                NavigationCameraState.TRANSITION_TO_OVERVIEW, NavigationCameraState.OVERVIEW, NavigationCameraState.IDLE -> binding.recenter.visibility =
                    View.VISIBLE
            }
        }

        naviViewModel.destinationPoi.observe(viewLifecycleOwner) { destination ->
            naviViewModel.findRoute(
                navigationLocationProvider.lastLocation, destination, isAssistanceRequest = true
            )
        }

        naviViewModel.destinationPlace.observe(viewLifecycleOwner) { place ->
            naviViewModel.searchPlace(
                navigationLocationProvider.lastLocation, place
            )
        }

        naviViewModel.suggestions.observe(viewLifecycleOwner) {
            renderMainSuggestions(it)
            if (it.isNotEmpty()) {
                mapMarkersManager.showSuggestions(it)
            } else {
                val status = naviViewModel.navigationState.value?.status
                if (status != NavigationStatus.ROUTE_CALCULATING &&
                    status != NavigationStatus.ROUTE_SET &&
                    status != NavigationStatus.SIMULATING_DRIVE
                ) {
                    mapMarkersManager.clearMarkers()
                }
            }
        }

        naviViewModel.searchInProgress.observe(viewLifecycleOwner) { inProgress ->
            binding.mainSearchProgress.visibility = if (inProgress) View.VISIBLE else View.GONE
        }

        naviViewModel.searchMessage.observe(viewLifecycleOwner) { message ->
            binding.mainSearchMessage.text = message.orEmpty()
        }

        naviViewModel.home.observe(viewLifecycleOwner) { home ->
            binding.navigateHomeShortcut.isEnabled = home != null
            binding.navigateHomeShortcut.alpha = if (home == null) 0.55f else 1f
        }

        naviViewModel.work.observe(viewLifecycleOwner) { work ->
            binding.navigateWorkShortcut.isEnabled = work != null
            binding.navigateWorkShortcut.alpha = if (work == null) 0.55f else 1f
        }

        naviViewModel.selectedSuggestion.observe(viewLifecycleOwner) { suggestion ->
            suggestion?.let(::renderMainPlaceDetail)
        }

        naviViewModel.detailInProgress.observe(viewLifecycleOwner) { inProgress ->
            if (inProgress && binding.mainPlaceDetail.visibility == View.VISIBLE) {
                binding.mainDetailSource.text = "Loading Tripadvisor details..."
            }
        }

        naviViewModel.navigationState.observe(viewLifecycleOwner) { state ->
            updateNavigationUi(state.status)
            if (state.mapStyle != appliedMapStyle) {
                applyMapStyle(state.mapStyle)
            }
            if (state.status == NavigationStatus.ROUTE_CALCULATING ||
                state.status == NavigationStatus.ROUTE_SET ||
                state.status == NavigationStatus.SIMULATING_DRIVE
            ) {
                state.destination?.let { mapMarkersManager.showDestination(it.point) }
            }
            // NaviViewModel's own RoutesObserver decides when replay actually starts; follow the
            // camera only once that real transition happens instead of guessing with a timer.
            if (lastObservedStatus != NavigationStatus.SIMULATING_DRIVE &&
                state.status == NavigationStatus.SIMULATING_DRIVE
            ) {
                navigationCamera.requestNavigationCameraToFollowing {
                    viewportDataSource.followingZoomPropertyOverride(17.0)
                    viewportDataSource.evaluate()
                }
            }
            lastObservedStatus = state.status
        }
    }

    private fun configureCompactNavigationComponents() {
        binding.maneuverView.updateManeuverViewOptions(
            ManeuverViewOptions.Builder()
                .primaryManeuverOptions(
                    ManeuverPrimaryOptions.Builder()
                        .textAppearance(R.style.NavigationManeuverPrimaryText)
                        .build()
                )
                .secondaryManeuverOptions(
                    ManeuverSecondaryOptions.Builder()
                        .textAppearance(R.style.NavigationManeuverSecondaryText)
                        .build()
                )
                .subManeuverOptions(
                    ManeuverSubOptions.Builder()
                        .textAppearance(R.style.NavigationManeuverSubText)
                        .build()
                )
                .stepDistanceTextAppearance(R.style.NavigationManeuverDistanceText)
                .build()
        )
        binding.tripProgressView.updateOptions(
            TripProgressViewOptions.Builder()
                .timeRemainingTextAppearance(R.style.NavigationTripProgressText)
                .distanceRemainingTextAppearance(R.style.NavigationTripProgressText)
                .estimatedArrivalTimeTextAppearance(R.style.NavigationTripProgressText)
                .build()
        )
    }

    private fun configureMainSearch() {
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchNow(binding.searchInput.text?.toString().orEmpty())
                hideKeyboard()
                true
            } else {
                false
            }
        }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(value: Editable?) {
                val query = value?.toString().orEmpty().trim()
                binding.searchClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                searchDebounceJob?.cancel()
                if (query.length >= 2) {
                    naviViewModel.clearSuggestionDetails()
                    renderSearchPanel(SearchPanelMode.SUGGESTIONS)
                    searchDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(1_000)
                        naviViewModel.searchDestinations(query)
                    }
                } else if (query.isEmpty()) {
                    hideMainSearchPanel(clearResults = false)
                }
            }
        })
        binding.searchClear.setOnClickListener {
            binding.searchInput.text?.clear()
            setActiveMainCategory(null)
            binding.searchClear.visibility = View.GONE
            hideMainSearchPanel(clearResults = true)
        }
        binding.mainDetailBack.setOnClickListener { showSuggestionList() }
        binding.mainDirections.setOnClickListener {
            mainSelectedSuggestionId?.let { suggestionId ->
                when (naviViewModel.selectSuggestion(suggestionId)) {
                    NavigationResultCode.ACCEPTED -> hideMainSearchPanel(clearResults = false)
                    else -> Toast.makeText(
                        requireContext(),
                        "Unable to start navigation",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        binding.mainAddLabel.setOnClickListener {
            naviViewModel.selectedSuggestion.value?.let(::showLabelChooser)
        }
        binding.navigateHomeShortcut.setOnClickListener { startSavedPlaceNavigation(SavedPlace.HOME) }
        binding.navigateWorkShortcut.setOnClickListener { startSavedPlaceNavigation(SavedPlace.WORK) }
    }

    private fun searchNow(query: String) {
        val normalized = query.trim()
        if (normalized.length < 2) return
        searchDebounceJob?.cancel()
        naviViewModel.clearSuggestionDetails()
        setActiveMainCategory(null)
        renderSearchPanel(SearchPanelMode.SUGGESTIONS)
        naviViewModel.searchDestinations(normalized)
    }

    private fun searchNearbyFromMain(category: NearbyCategory) {
        searchDebounceJob?.cancel()
        hideKeyboard()
        binding.searchInput.clearFocus()
        setActiveMainCategory(category)
        binding.searchClear.visibility = View.VISIBLE
        renderSearchPanel(SearchPanelMode.SUGGESTIONS)
        naviViewModel.clearSuggestionDetails()
        naviViewModel.searchNearby(category.code)
    }

    private fun renderMainSuggestions(suggestions: List<NavigationSuggestion>) {
        val container = binding.mainSuggestionList
        container.removeAllViews()
        suggestions.forEach { suggestion ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { showMainPlaceDetail(suggestion) }
            }
            row.addView(TextView(requireContext()).apply {
                text = suggestion.name
                setTextColor(android.graphics.Color.rgb(15, 23, 42))
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            row.addView(TextView(requireContext()).apply {
                text = suggestion.listSummaryText()
                setTextColor(android.graphics.Color.rgb(71, 85, 105))
                textSize = 11f
            })
            container.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            container.addView(View(requireContext()).apply {
                setBackgroundColor(android.graphics.Color.rgb(226, 232, 240))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ))
        }
    }

    private fun renderSearchPanel(mode: SearchPanelMode) {
        binding.mainSearchPanel.showIf(mode != SearchPanelMode.HIDDEN)
        binding.mainSuggestionContent.showIf(mode == SearchPanelMode.SUGGESTIONS)
        binding.mainPlaceDetail.showIf(mode == SearchPanelMode.DETAIL)
    }

    private fun bindMainPlaceDetail(
        suggestion: NavigationSuggestion,
        source: String,
        resetPhoto: Boolean
    ) {
        binding.mainDetailName.text = suggestion.name
        binding.mainDetailMeta.text = suggestion.detailSummaryText()
        binding.mainDetailAddress.text = suggestion.address?.takeIf { it.isNotBlank() }
            ?: "Address unavailable"
        binding.mainDetailSource.text = source
        if (resetPhoto) binding.mainDetailPhoto.visibility = View.GONE
    }

    private fun showMainPlaceDetail(suggestion: NavigationSuggestion) {
        mainSelectedSuggestionId = suggestion.suggestionId
        renderSearchPanel(SearchPanelMode.DETAIL)
        bindMainPlaceDetail(suggestion, "Loading Tripadvisor details...", resetPhoto = true)
        binding.mapView.mapboxMap.setCamera(
            CameraOptions.Builder().center(suggestion.point).zoom(15.0).build()
        )
        mapMarkersManager.adjustMarkersForOpenCard()
        naviViewModel.showSuggestionDetails(suggestion.suggestionId)
    }

    private fun renderMainPlaceDetail(suggestion: NavigationSuggestion) {
        if (mainSelectedSuggestionId != suggestion.suggestionId) return
        bindMainPlaceDetail(
            suggestion = suggestion,
            source = if (suggestion.rating != null || suggestion.photoUrl != null) {
                "Rating and photo by Tripadvisor"
            } else {
                ""
            },
            resetPhoto = false
        )
        loadMainPlacePhoto(suggestion)
    }

    private fun showSuggestionList() {
        naviViewModel.clearSuggestionDetails()
        mainSelectedSuggestionId = null
        renderSearchPanel(SearchPanelMode.SUGGESTIONS)
        mapMarkersManager.adjustMarkersForClosedCard()
    }

    private fun hideMainSearchPanel(clearResults: Boolean) {
        searchDebounceJob?.cancel()
        photoLoadJob?.cancel()
        mainSelectedSuggestionId = null
        renderSearchPanel(SearchPanelMode.HIDDEN)
        binding.mainDetailPhoto.visibility = View.GONE
        naviViewModel.clearSuggestionDetails()
        if (clearResults) naviViewModel.clearSearch()
        mapMarkersManager.adjustMarkersForClosedCard()
    }

    private fun showLabelChooser(suggestion: NavigationSuggestion) {
        AlertDialog.Builder(requireContext())
            .setItems(arrayOf("Home", "Work")) { _, which ->
                confirmLabelReplacement(SavedPlace.values()[which], suggestion)
            }
            .show()
    }

    private fun confirmLabelReplacement(savedPlace: SavedPlace, suggestion: NavigationSuggestion) {
        val configured = when (savedPlace) {
            SavedPlace.HOME -> naviViewModel.home.value != null
            SavedPlace.WORK -> naviViewModel.work.value != null
        }
        if (!configured) {
            saveLabel(savedPlace, suggestion)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Replace ${savedPlace.label}?")
            .setMessage("Replace the saved ${savedPlace.label} location with ${suggestion.name}?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Replace") { _, _ -> saveLabel(savedPlace, suggestion) }
            .show()
    }

    private fun saveLabel(savedPlace: SavedPlace, suggestion: NavigationSuggestion) {
        when (savedPlace) {
            SavedPlace.HOME -> naviViewModel.setHome(
                suggestion.name,
                suggestion.address,
                suggestion.point
            )
            SavedPlace.WORK -> naviViewModel.setWork(
                suggestion.name,
                suggestion.address,
                suggestion.point
            )
        }
        Toast.makeText(
            requireContext(),
            "${savedPlace.label} saved",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun startSavedPlaceNavigation(savedPlace: SavedPlace) {
        val result = when (savedPlace) {
            SavedPlace.HOME -> naviViewModel.startNavigatingHome()
            SavedPlace.WORK -> naviViewModel.startNavigatingWork()
        }
        if (result == NavigationResultCode.ACCEPTED) {
            hideMainSearchPanel(clearResults = false)
        } else {
            Toast.makeText(
                requireContext(),
                "${savedPlace.label} is not configured",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun loadMainPlacePhoto(suggestion: NavigationSuggestion) {
        photoLoadJob?.cancel()
        binding.mainDetailPhoto.visibility = View.GONE
        val url = suggestion.photoUrl ?: return
        photoLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    URL(url).openConnection().apply {
                        connectTimeout = 12_000
                        readTimeout = 15_000
                    }.getInputStream().use { input -> BitmapFactory.decodeStream(input) }
                }.getOrNull()
            }
            if (bitmap != null && mainSelectedSuggestionId == suggestion.suggestionId) {
                binding.mainDetailPhoto.setImageBitmap(bitmap)
                binding.mainDetailPhoto.visibility = View.VISIBLE
            }
        }
    }

    private fun NavigationSuggestion.listSummaryText(): String {
        val categoryText = category?.name
            ?.lowercase(Locale.US)
            ?.replace('_', ' ')
            ?.replaceFirstChar { it.titlecase(Locale.US) }
            ?: "Place"
        val distanceText = distanceMeters?.let { meters ->
            if (meters >= 1_000) {
                String.format(Locale.US, "%.1f km", meters / 1_000)
            } else {
                "${meters.toInt()} m"
            }
        } ?: "Distance unavailable"
        return "$categoryText - $distanceText"
    }

    private fun NavigationSuggestion.detailSummaryText(): String {
        val ratingText = rating?.let { value ->
            val reviews = reviewCount?.let { count -> " ($count)" }.orEmpty()
            String.format(Locale.US, "%.1f%s", value, reviews)
        }
        return listOfNotNull(listSummaryText(), ratingText).joinToString(" - ")
    }

    private fun hideKeyboard() {
        binding.searchInput.clearFocus()
        val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager
        inputMethodManager?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    private fun View.showIf(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun NavigationStatus.showsNavigationUi(): Boolean =
        this == NavigationStatus.ROUTE_SET || this == NavigationStatus.SIMULATING_DRIVE

    private fun NavigationStatus.blocksSearchUi(): Boolean =
        this == NavigationStatus.ROUTE_CALCULATING || showsNavigationUi()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun setActiveMainCategory(category: NearbyCategory?) {
        mapOf(
            NearbyCategory.RESTAURANT to binding.categoryRestaurantChip,
            NearbyCategory.HOTEL to binding.categoryHotelChip,
            NearbyCategory.HOSPITAL to binding.categoryHospitalChip,
            NearbyCategory.GAS_STATION to binding.categoryGasChip,
            NearbyCategory.CONVENIENCE_STORE to binding.categoryConvenienceChip
        ).forEach { (candidate, chip) ->
            chip.isSelected = candidate == category
        }
    }

    private fun updateBottomNavigationCard(routeProgress: RouteProgress) {
        val distanceRemaining = routeProgress.distanceRemaining.toDouble().coerceAtLeast(0.0)
        val totalDistance = routeTotalDistanceMeters
            ?.coerceAtLeast(distanceRemaining)
            ?: distanceRemaining.also { routeTotalDistanceMeters = it }
        routeTotalDistanceMeters = totalDistance
        val progress = if (totalDistance > 0.0) {
            (((totalDistance - distanceRemaining) / totalDistance) * 100.0)
                .roundToInt()
                .coerceIn(0, 100)
        } else {
            100
        }
        binding.routeProgressIndicator.progress = progress

        val currentSpeedKmh = (
                (navigationLocationProvider.lastLocation?.speed ?: 0.0) * 3.6
                ).roundToInt().coerceAtLeast(0)
        binding.currentSpeedText.text = "$currentSpeedKmh km/h"

        val durationRemaining = routeProgress.durationRemaining.toDouble()
        val averageSpeedKmh = if (durationRemaining > 0.0) {
            distanceRemaining / durationRemaining * 3.6
        } else {
            0.0
        }
        binding.trafficStatusText.text = when {
            averageSpeedKmh <= 0.0 -> "Traffic route"
            averageSpeedKmh < 20.0 -> "Traffic: heavy"
            averageSpeedKmh < 40.0 -> "Traffic: slow"
            else -> "Traffic: flowing"
        }
    }

    private fun updateNavigationUi(status: NavigationStatus) {
        val navigationVisible = status.showsNavigationUi()
        val searchVisible = !status.blocksSearchUi()
        binding.searchEntry.showIf(searchVisible)
        binding.savedPlaceShortcuts.showIf(searchVisible)
        binding.quickPlaces.showIf(searchVisible)
        if (!searchVisible) renderSearchPanel(SearchPanelMode.HIDDEN)
        binding.changeStyle.visibility = View.VISIBLE
        binding.maneuverCard.showIf(navigationVisible)
        binding.maneuverView.showIf(navigationVisible)
        binding.speedLimitView.showIf(navigationVisible)
        binding.tripProgressCard.showIf(navigationVisible)
        binding.searchInput.isEnabled = status != NavigationStatus.ROUTE_CALCULATING
        binding.searchInput.hint = when (status) {
            NavigationStatus.ROUTE_CALCULATING -> "Calculating route…"
            NavigationStatus.SHOWING_SUGGESTIONS -> "Choose a nearby place"
            NavigationStatus.UNAVAILABLE -> "Search unavailable — tap for details"
            else -> "Search destination or category"
        }
        if (!navigationVisible) {
            binding.soundButton.visibility = View.GONE
        }
    }

    private fun showPopupWindow(anchor: View) {
        PopupWindow(anchor.context).apply {
            isOutsideTouchable = true
            val inflater = LayoutInflater.from(anchor.context)
            contentView = inflater.inflate(R.layout.popup_layer, null).apply {
                measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
            }
        }.also { popupWindow ->
            popupWindow.width = binding.mapView.width * 2 / 3
            popupWindow.showAtLocation(
                anchor, Gravity.BOTTOM, 0, 0
            )
            changeStyleLayer(popupWindow)
        }
    }

    private fun changeStyleLayer(popupWindow: PopupWindow) {
        popupWindow.contentView.findViewById<ImageView>(R.id.img_normal_layer).setOnClickListener {
            NavigationManager.setMapStyle(MapStyleMode.NORMAL.code)
            popupWindow.dismiss()
        }
        popupWindow.contentView.findViewById<ImageView>(R.id.img_standard_layer)
            .setOnClickListener {
                NavigationManager.setMapStyle(MapStyleMode.STANDARD_3D.code)
                popupWindow.dismiss()
            }
        popupWindow.contentView.findViewById<ImageView>(R.id.img_traffic_layer).setOnClickListener {
            NavigationManager.setMapStyle(MapStyleMode.TRAFFIC.code)
            popupWindow.dismiss()
        }
        popupWindow.contentView.findViewById<ImageView>(R.id.img_light_layer).setOnClickListener {
            NavigationManager.setMapStyle(MapStyleMode.LIGHT.code)
            popupWindow.dismiss()
        }
        popupWindow.contentView.findViewById<ImageView>(R.id.img_satellite_layer)
            .setOnClickListener {
                NavigationManager.setMapStyle(MapStyleMode.SATELLITE.code)
                popupWindow.dismiss()
            }
    }

    private fun applyMapStyle(styleMode: MapStyleMode, persist: Boolean = true) {
        appliedMapStyle = styleMode
        val styleUri = when (styleMode) {
            MapStyleMode.NORMAL -> Style.MAPBOX_STREETS
            MapStyleMode.STANDARD_3D -> Style.STANDARD
            MapStyleMode.TRAFFIC -> Style.TRAFFIC_DAY
            MapStyleMode.LIGHT -> Style.LIGHT
            MapStyleMode.SATELLITE -> Style.SATELLITE
        }
        loadMapStyle(styleUri, styleMode)
        if (persist) {
            SharePreferences.saveIntPreferences(
                sharedPreferences.edit(),
                Constant.STYLE,
                styleMode.code
            )
        }
    }

    private fun loadMapStyle(styleUri: String, styleMode: MapStyleMode) {
        binding.mapView.mapboxMap.loadStyle(styleUri) { style ->
            if (styleMode == MapStyleMode.STANDARD_3D) {
                style.setStyleImportConfigProperty("basemap", "lightPreset", Value("dawn"))
                style.setStyleImportConfigProperty("basemap", "theme", Value("faded"))
            }

            routeLineView.initializeLayers(style)
            val currentRoutes = mapboxNavigation.getNavigationRoutes()
            if (currentRoutes.isNotEmpty()) {
                routeLineApi.setNavigationRoutes(currentRoutes) { drawData ->
                    routeLineView.renderRouteDrawData(style, drawData)
                }
            }
        }
    }

    private fun updateCamera(location: Location) {
        val mapAnimationOptions = MapAnimationOptions.Builder().duration(1500L).build()
        binding.mapView.camera.easeTo(
            CameraOptions.Builder().center(Point.fromLngLat(location.longitude, location.latitude))
                .zoom(17.0).pitch(60.0).build(), mapAnimationOptions
        )
    }

    private fun resetToFlowing() {
        if (jobResetToFlowing != null) {
            jobResetToFlowing?.cancel()
        }
        Log.d(TAG, "resetToFlowing: $isSimulation - ${navigationCamera.state}")
        if (isSimulation && (navigationCamera.state == NavigationCameraState.IDLE || navigationCamera.state == NavigationCameraState.TRANSITION_TO_OVERVIEW || navigationCamera.state == NavigationCameraState.OVERVIEW)) {
            jobResetToFlowing = lifecycleScope.launch {
                navigationCamera.requestNavigationCameraToFollowing()
            }
        }
    }

    private fun hideUiElement() {
        // hide UI elements
        binding.soundButton.visibility = View.GONE
        binding.maneuverCard.visibility = View.GONE
        binding.maneuverView.visibility = View.GONE
        //binding.routeOverview.visibility = View.GONE
        binding.tripProgressCard.visibility = View.GONE
    }

    override fun onDestroyView() {
        jobResetToFlowing?.cancel()
        mockLocationUpdateJob?.cancel()
        searchDebounceJob?.cancel()
        photoLoadJob?.cancel()
        mapMarkersManager.onSuggestionClickListener = null
        mapMarkersManager.clearMarkers()

        disconnectForegroundConnections()
        binding.mapView.location.removeOnIndicatorPositionChangedListener(onPositionChangedListener)
        binding.mapView.gestures.removeOnMoveListener(onMoveListener)
        routeLineApi.cancel()
        routeLineView.cancel()
        _binding = null
        super.onDestroyView()
    }

    private fun disconnectForegroundConnections() {
        if (mockLocationBound) {
            activity?.unbindService(mServiceConnection)
            mockLocationBound = false
        }
        isLocationConnected = false

        if (naviAidlBound) {
            activity?.unbindService(aidlConnection)
            naviAidlBound = false
        }
        naviAIDL = null

        mFAutoCar?.disconnect()
        mFAutoCar = null
        mFAutoShareDataManager = null
        fAutoCarConnected = false
    }

    private class MapMarkersManager(mapView: MapView, context: Context) {

        private val suggestionAnnotations = mutableMapOf<String, NavigationSuggestion>()
        private val mapboxMap: MapboxMap = mapView.getMapboxMap()
        private val pointAnnotationManager = mapView.annotations.createPointAnnotationManager(null)
        private val markerIcon = requireNotNull(
            ContextCompat.getDrawable(context, R.drawable.ic_red_marker)
        ) { "Missing map marker drawable" }.toBitmap()
        private var destinationPoint: Point? = null

        var onSuggestionClickListener: ((NavigationSuggestion) -> Unit)? = null

        init {
            pointAnnotationManager.addClickListener {
                suggestionAnnotations[it.id]?.let { result ->
                    onSuggestionClickListener?.invoke(result)
                }
                true
            }
        }

        fun clearMarkers() {
            pointAnnotationManager.deleteAll()
            suggestionAnnotations.clear()
            destinationPoint = null
        }

        fun adjustMarkersForOpenCard() {
            val coordinates = suggestionAnnotations.values.map { it.point }
            if (coordinates.isEmpty()) return
            val cameraOptions = mapboxMap.cameraForCoordinates(
                coordinates, MARKERS_INSETS_OPEN_CARD, bearing = null, pitch = null
            )
            mapboxMap.setCamera(cameraOptions)
        }

        fun adjustMarkersForClosedCard() {
            val coordinates = suggestionAnnotations.values.map { it.point }
            if (coordinates.isEmpty()) return
            val cameraOptions = mapboxMap.cameraForCoordinates(
                coordinates, MARKERS_INSETS, bearing = null, pitch = null
            )
            mapboxMap.setCamera(cameraOptions)
        }

        fun showSuggestions(results: List<NavigationSuggestion>) {
            clearMarkers()
            if (results.isEmpty()) return
            val coordinates = ArrayList<Point>(results.size)
            results.forEach { result ->
                val options = PointAnnotationOptions()
                    .withPoint(result.point)
                    .withIconImage(markerIcon)
                    .withIconAnchor(IconAnchor.BOTTOM)
                val annotation = pointAnnotationManager.create(options)
                suggestionAnnotations[annotation.id] = result
                coordinates.add(result.point)
            }
            mapboxMap.cameraForCoordinates(
                coordinates,
                CameraOptions.Builder().build(),
                MARKERS_INSETS,
                null,
                null
            ) {
                mapboxMap.setCamera(it)
            }
        }

        fun showDestination(point: Point) {
            if (destinationPoint == point) return
            clearMarkers()
            pointAnnotationManager.create(
                PointAnnotationOptions()
                    .withPoint(point)
                    .withIconImage(markerIcon)
                    .withIconAnchor(IconAnchor.BOTTOM)
            )
            destinationPoint = point
        }

    }

    private companion object {
        const val PACKAGE_NAME = "com.car.ivi.mocklocation"
        const val BIND_ACTION_NAME = "ivi.car.action.UpdateLocation"
        private const val BUTTON_ANIMATION_DURATION = 1500L
        const val PERMISSIONS_REQUEST_LOCATION = 0
        val MARKERS_BOTTOM_OFFSET = 176.0
        val MARKERS_EDGE_OFFSET = 64.0
        val PLACE_CARD_HEIGHT = 300.0

        val MARKERS_INSETS = EdgeInsets(
            MARKERS_EDGE_OFFSET, MARKERS_EDGE_OFFSET, MARKERS_BOTTOM_OFFSET, MARKERS_EDGE_OFFSET
        )

        val MARKERS_INSETS_OPEN_CARD = EdgeInsets(
            MARKERS_EDGE_OFFSET, MARKERS_EDGE_OFFSET, PLACE_CARD_HEIGHT, MARKERS_EDGE_OFFSET
        )

    }
}