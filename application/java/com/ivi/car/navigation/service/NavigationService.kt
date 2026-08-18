package com.ivi.car.navigation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.google.gson.Gson
import com.ivi.car.navigation.controller.NavigationManager
import com.ivi.car.navigation.model.Navigation
import com.ivi.car.navigation.ui.MainActivity
import com.ivi.car.navigation.util.Utils
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.trip.model.RouteProgressState
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationProvider
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import dagger.hilt.android.AndroidEntryPoint
import fauto.car.FAutoCar
import fauto.car.clustercontrol.FAutoCarClusterControlManager
import fauto.car.sharedata.FAutoShareDataManager
import org.json.JSONObject
import javax.inject.Inject

@AndroidEntryPoint
class NavigationService: Service() {
    private val TAG = NavigationService::class.simpleName
    private lateinit var mapboxNavigation: MapboxNavigation
    @Inject
    lateinit var maneuverApi: MapboxManeuverApi
    private var navigation: Navigation = Navigation()
    private var fAutoCar: FAutoCar? = null
    private var fAutoShareDataManager: FAutoShareDataManager? = null
    private var carClusterManager: FAutoCarClusterControlManager? = null
    private var gson: Gson? = null
    private var routeObserverRegistered = false
    // Feed NavigationManager's widgetXxx mirror (consumed by MapWidgetSurfaceService for the
    // launcher's live map widget) independently of NaviFragment - NaviFragment's own observers
    // only run while it is resumed (see requireMapboxNavigation's onResumedObserver), so without
    // this the widget freezes the moment the user leaves the app to look at the home screen,
    // which is exactly when the widget is meant to be useful. Does not touch NaviFragment or its
    // observers.
    private var widgetRoutesObserverRegistered = false
    private var widgetLocationObserverRegistered = false
    private var carConnectionRequested = false

    private val serviceConnection: ServiceConnection = object: ServiceConnection{
        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?
        ) {
            Log.i(TAG,"onServiceConnected")
            fAutoShareDataManager = fAutoCar
                ?.getFAutoCarManager(FAutoShareDataManager.SHARE_DATA_SERVICE)
                ?.let { manager -> manager as? FAutoShareDataManager }
            carClusterManager =
                fAutoCar?.getFAutoCarManager(FAutoCarClusterControlManager.CLUSTERCONTROL_SERVICE)
                        as? FAutoCarClusterControlManager

            Log.i(TAG,"fAutoShareDataManager != null : ${fAutoShareDataManager!= null}")

        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG,"onServiceDisconnected")
            carConnectionRequested = false
            fAutoCar = null
            fAutoShareDataManager = null
            carClusterManager = null
        }

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        val notification = buildNotification()
        startForeground(1, notification)

        if (!carConnectionRequested) {
            connectToCarService()
        }
        gson = Gson()
        if (MapboxNavigationProvider.isCreated()) {
            mapboxNavigation = MapboxNavigationProvider.retrieve()
            if (!routeObserverRegistered) {
                mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
                routeObserverRegistered = true
            }
            if (!widgetRoutesObserverRegistered) {
                mapboxNavigation.registerRoutesObserver(widgetRoutesObserver)
                widgetRoutesObserverRegistered = true
            }
            if (!widgetLocationObserverRegistered) {
                mapboxNavigation.registerLocationObserver(widgetLocationObserver)
                widgetLocationObserverRegistered = true
            }

            Log.i(TAG, "Service đã kết nối vào Navigation Session có sẵn")
        } else {
            Log.w(TAG, "Navigation chưa được khởi tạo từ Activity!")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "navigation_service_channel"
        createNotificationChannel(channelId)

        return Notification.Builder(this, channelId)
            .setContentTitle("Navigation")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel(channelId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Navigation", NotificationManager.IMPORTANCE_LOW
            )
            val manager: NotificationManager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }


    override fun onCreate() {
        super.onCreate()
    }

    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        // update the camera position to account for the progressed fragment of the route
        Log.i(TAG,"routeProgressObserver change ....")
        val previousStepRoad = navigation.getStepRoad()
        val previousStepDistance = navigation.getStepDistance()
        val previousType = navigation.getType()
        val previousDistance = navigation.getDistance()
        var stepRoad: String? = null
        var stepDistanceRemaining: Double? = null
        var maneuverType: String? = null
        var maneuverModifier: String? = null
        maneuverApi.getManeuvers(routeProgress).fold({ error ->
            Log.i(TAG,"RouteProgressObserver - error : $error")
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
            maneuverModifier = maneuverModifier
        )
        NavigationManager.updateProgress(
            routeProgress.distanceRemaining.toDouble(),
            routeProgress.durationRemaining.toInt()
        )
        // Widget mirror (see widgetRoutesObserver/widgetLocationObserver below) - keeps the
        // launcher's maneuver/trip-progress card live while NaviFragment isn't resumed.
        NavigationManager.updateWidgetRouteProgress(routeProgress)
        val canUpdate =
            previousStepRoad != navigation.getStepRoad() ||
                    kotlin.math.abs(previousStepDistance - navigation.getStepDistance()) >= 1.0 ||
                    previousType != navigation.getType() ||
                    kotlin.math.abs(previousDistance - navigation.getDistance()) >= 1.0
        Log.i(TAG,"Can update : $canUpdate")
        if (canUpdate) {
            publishTurnByTurnToLauncher()
            if (!MainActivity.isRunning) {
                sendNaviData()
            }
        }

        if (routeProgress.currentState == RouteProgressState.COMPLETE) {
            NavigationManager.stopNavigation()
            val destination = navigation.getDestination()
            navigation = Navigation().apply {
                setDestination(destination)
                setStepDistance(0.0)
            }
            publishTurnByTurnToLauncher(arrived = true)
            if (!MainActivity.isRunning) {
                sendNaviData()
            }
            unregisterObserver()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return@RouteProgressObserver
        }
    }

    // Widget mirror only (see field doc above) - NaviFragment.routesObserver still owns the
    // in-app route line/camera/ensureNavigationServiceRunning() logic untouched, this just
    // relays the same route-list change to NavigationManager's widget-facing state.
    private val widgetRoutesObserver = RoutesObserver { routeUpdateResult ->
        NavigationManager.updateWidgetRoutes(routeUpdateResult.navigationRoutes)
        if (routeUpdateResult.navigationRoutes.isEmpty()) {
            NavigationManager.updateWidgetRouteProgress(null)
        }
    }

    // Widget mirror only. Puck position (widgetLocationMatcherResult) is relayed unconditionally
    // - it is the same authoritative data NaviFragment's own locationObserver would relay, so
    // there is nothing to fight over. Camera is different: while NaviFragment is resumed it
    // already mirrors its own MapView's real cameraState (correctly reflecting overview/
    // following/free-pan), which is strictly better than anything this headless service could
    // approximate - so the synthetic follow-camera below only fires while MainActivity isn't
    // running, to avoid the two sources fighting over widgetCamera when the app is foregrounded.
    private val widgetLocationObserver = object : LocationObserver {
        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            NavigationManager.updateWidgetLocationMatcherResult(locationMatcherResult)
            if (!MainActivity.isRunning) {
                val enhancedLocation = locationMatcherResult.enhancedLocation
                NavigationManager.updateWidgetCamera(
                    NavigationManager.WidgetCameraSnapshot(
                        center = Point.fromLngLat(enhancedLocation.longitude, enhancedLocation.latitude),
                        zoom = WIDGET_FOLLOWING_ZOOM,
                        bearing = enhancedLocation.bearing?.toDouble() ?: 0.0,
                        pitch = WIDGET_FOLLOWING_PITCH
                    )
                )
            }
        }

        override fun onNewRawLocation(rawLocation: com.mapbox.common.location.Location) {}
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        unregisterObserver()
        fAutoCar?.disconnect()
        fAutoCar = null
        carConnectionRequested = false
        fAutoShareDataManager = null
        carClusterManager = null
        super.onDestroy()
    }

    private fun connectToCarService() {
        carConnectionRequested = true
        fAutoCar = FAutoCar.createFAutoCar(this, serviceConnection)
        if (fAutoCar == null) {
            carConnectionRequested = false
        } else {
            fAutoCar?.connect()
        }
    }

    @RequiresPermission("fauto.car.permission.CONTROL_FSHARESERVICE")
    private fun sendNaviData() {
        val normalizeNavi = Utils.normalizeData(navigation)
        Log.i(TAG,"sendNaviData")
        sendNavDataToSomeIp(normalizeNavi)
        val data = Utils.convertToJsonData(normalizeNavi)
        fAutoShareDataManager?.onNaviDataReceived(data)
    }

    private fun publishTurnByTurnToLauncher(arrived: Boolean = false) {
        val normalized = Utils.normalizeData(navigation)
        val data = JSONObject()
            .put("currentRoad", normalized.getCurrentRoad())
            .put("destination", normalized.getDestination())
            .put("duration", normalized.getDuration())
            .put("distance", normalized.getDistance())
            .put("distanceUnit", normalized.getDistanceUnit()?.name ?: "METERS")
            .put("stepRoad", normalized.getStepRoad())
            .put("stepDuration", normalized.getStepDuration())
            .put("stepDistance", if (arrived) 0.0 else normalized.getStepDistance())
            .put("stepUnit", normalized.getStepUnit()?.name ?: "METERS")
            .put("cue", normalized.getCue())
            .put("lane", normalized.getLane()?.name)
            .put("type", if (arrived) "DESTINATION" else normalized.getType()?.name ?: "UNKNOWN")
        // Keep the turn-by-turn fields at the root during the launcher migration:
        // existing LauncherFragment versions deserialize the callback directly as Navigation.
        // Newer versions can select the channel and deserialize the nested data object instead.
        val payload = JSONObject(data.toString())
            .put("channel", "turn-by-turn")
            .put("data", data)
            .toString()
        LauncherTurnByTurnBus.publish(payload)
    }

    private fun unregisterObserver() {
        if (::mapboxNavigation.isInitialized) {
            if (routeObserverRegistered) {
                mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
                routeObserverRegistered = false
            }
            if (widgetRoutesObserverRegistered) {
                mapboxNavigation.unregisterRoutesObserver(widgetRoutesObserver)
                widgetRoutesObserverRegistered = false
            }
            if (widgetLocationObserverRegistered) {
                mapboxNavigation.unregisterLocationObserver(widgetLocationObserver)
                widgetLocationObserverRegistered = false
            }
            Log.i(TAG, "Đã hủy đăng ký observer và dừng Trip Session")
        }
    }

    private fun sendNavDataToSomeIp(navigation: Navigation) {
        gson?.let {
            val messageJson = JSONObject()
            val navigationJsonStr = it.toJson(navigation)
            messageJson.put("Navigation", JSONObject(navigationJsonStr))
            Log.d(TAG, "Sending navigation update to cluster")
            carClusterManager?.fireNavPictureUpdatesEvent(FAutoCarClusterControlManager.PICTURE_UPDATE_EVENT_HEADER_DISPLAY_AVAILABLE, messageJson.toString())
        }
    }

    private companion object {
        // Matches NaviFragment's own hardcoded following-camera values (updateCamera(), and the
        // followingZoomPropertyOverride(17.0) applied when simulation starts) so the widget's
        // synthetic background camera looks consistent with what the in-app map itself shows.
        const val WIDGET_FOLLOWING_ZOOM = 17.0
        const val WIDGET_FOLLOWING_PITCH = 60.0
    }
}