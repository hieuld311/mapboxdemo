package com.ivi.car.navigation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.ivi.car.navigation.controller.NavigationManager
import com.ivi.car.navigation.model.Navigation
import com.ivi.car.navigation.ui.MainActivity
import com.ivi.car.navigation.util.Utils
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.geojson.utils.PolylineUtils
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapSnapshotInterface
import com.mapbox.maps.MapSnapshotOptions
import com.mapbox.maps.Size
import com.mapbox.maps.Snapshotter
import com.mapbox.maps.Style
import com.mapbox.navigation.base.trip.model.RouteProgressState
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationProvider
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import dagger.hilt.android.AndroidEntryPoint
import fauto.car.FAutoCar
import fauto.car.clustercontrol.FAutoCarClusterControlManager
import fauto.car.sharedata.FAutoShareDataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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
    private var carConnectionRequested = false

    // Periodic map-snapshot capture (see startMapSnapshotLoop()/captureSnapshot()) - runs
    // independently of NaviFragment so the launcher's focus-card image keeps updating even
    // while the app UI isn't visible, matching this service's own always-alive lifecycle.
    private val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var snapshotter: Snapshotter? = null
    private var snapshotJob: Job? = null
    private var locationObserverRegistered = false
    private var latestSnapshotLocation: Location? = null
    private val routeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2F76F2")
        style = Paint.Style.STROKE
        strokeWidth = ROUTE_LINE_WIDTH_PX
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val snapshotLocationObserver = object : LocationObserver {
        override fun onNewRawLocation(rawLocation: Location) {}
        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            latestSnapshotLocation = locationMatcherResult.enhancedLocation
        }
    }

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
            if (!locationObserverRegistered) {
                mapboxNavigation.registerLocationObserver(snapshotLocationObserver)
                locationObserverRegistered = true
            }
            startMapSnapshotLoop()

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

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        unregisterObserver()
        snapshotScope.cancel()
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
            if (locationObserverRegistered) {
                mapboxNavigation.unregisterLocationObserver(snapshotLocationObserver)
                locationObserverRegistered = false
            }
            Log.i(TAG, "Đã hủy đăng ký observer và dừng Trip Session")
        }
        stopMapSnapshotLoop()
    }

    private fun startMapSnapshotLoop() {
        if (snapshotJob != null) return
        val options = MapSnapshotOptions.Builder()
            .size(Size(SNAPSHOT_WIDTH_DP, SNAPSHOT_HEIGHT_DP))
            .pixelRatio(resources.displayMetrics.density)
            .build()
        snapshotter = Snapshotter(this, options).apply {
            setStyleUri(Style.STANDARD)
        }
        Log.i(TAG, "startMapSnapshotLoop: started | size=${SNAPSHOT_WIDTH_DP}x$SNAPSHOT_HEIGHT_DP" +
                "dp, intervalMs=$SNAPSHOT_INTERVAL_MS")
        snapshotJob = snapshotScope.launch {
            while (true) {
                delay(SNAPSHOT_INTERVAL_MS)
                captureSnapshot()
            }
        }
    }

    private fun stopMapSnapshotLoop() {
        val wasRunning = snapshotJob != null
        snapshotJob?.cancel()
        snapshotJob = null
        snapshotter?.destroy()
        snapshotter = null
        if (wasRunning) {
            // Otherwise the launcher's focus card would keep showing the last captured
            // route/puck frame forever after the trip ends, since no further snapshot ever
            // arrives to replace it.
            publishMapSnapshotCleared()
        }
    }

    private fun publishMapSnapshotCleared() {
        val payload = JSONObject()
            .put("channel", "map-snapshot")
            .put("data", JSONObject().put("active", false))
            .toString()
        Log.i(TAG, "publishMapSnapshotCleared: navigation stopped, clearing launcher snapshot")
        LauncherTurnByTurnBus.publish(payload)
    }

    private fun captureSnapshot() {
        val location = latestSnapshotLocation ?: run {
            Log.d(TAG, "captureSnapshot: skipped, no location yet")
            return
        }
        val activeSnapshotter = snapshotter ?: run {
            Log.d(TAG, "captureSnapshot: skipped, snapshotter not ready")
            return
        }
        val bearing = location.bearing ?: 0.0
        activeSnapshotter.setCamera(
            CameraOptions.Builder()
                .center(Point.fromLngLat(location.longitude, location.latitude))
                .zoom(SNAPSHOT_ZOOM)
                .bearing(bearing)
                .pitch(0.0)
                .build()
        )
        // Route geometry captured now, at the same camera the snapshot renders with - the
        // Snapshotter is a headless renderer with no LocationComponent/route-line plugin of its
        // own, so the puck and route aren't part of the style; they're drawn onto the returned
        // bitmap afterward using MapSnapshotInterface.pixelForCoordinate for the route and a
        // fixed center point for the puck (the camera is always centered on the puck itself).
        val routeGeometry = mapboxNavigation.getNavigationRoutes()
            .firstOrNull()?.directionsRoute?.geometry()
        Log.d(TAG, "captureSnapshot: requesting | hasRoute=${routeGeometry != null}, " +
                "bearing=$bearing, lat=${location.latitude}, lng=${location.longitude}")
        activeSnapshotter.start { snapshot: MapSnapshotInterface? ->
            if (snapshot == null) {
                Log.w(TAG, "captureSnapshot: snapshot failed, MapSnapshotInterface is null")
                return@start
            }
            val rawBitmap = snapshot.bitmap()
            if (rawBitmap == null) {
                Log.w(TAG, "captureSnapshot: snapshot failed, no bitmap returned")
                return@start
            }
            val annotated = annotateSnapshot(rawBitmap, snapshot, routeGeometry, bearing)
            Log.i(TAG, "captureSnapshot: captured ${annotated.width}x${annotated.height} bitmap, " +
                    "publishing to launcher")
            publishMapSnapshotToLauncher(annotated)
        }
    }

    private fun annotateSnapshot(
        rawBitmap: Bitmap,
        snapshot: MapSnapshotInterface,
        routeGeometry: String?,
        puckBearing: Double
    ): Bitmap {
        val bitmap = rawBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)

        if (routeGeometry != null) {
            val routePoints = PolylineUtils.decode(routeGeometry, ROUTE_GEOMETRY_PRECISION)
            if (routePoints.size >= 2) {
                val path = Path()
                routePoints.forEachIndexed { index, point ->
                    val screen = snapshot.pixelForCoordinate(point)
                    if (index == 0) {
                        path.moveTo(screen.x.toFloat(), screen.y.toFloat())
                    } else {
                        path.lineTo(screen.x.toFloat(), screen.y.toFloat())
                    }
                }
                canvas.drawPath(path, routeLinePaint)
            }
        }

        // The camera is always centered on latestSnapshotLocation, so the puck's screen
        // position is always the bitmap's exact center - no pixelForCoordinate lookup needed.
        drawPuck(canvas, bitmap.width / 2f, bitmap.height / 2f, puckBearing)
        return bitmap
    }

    private fun drawPuck(canvas: Canvas, centerX: Float, centerY: Float, bearingDegrees: Double) {
        val puckDrawable = ContextCompat.getDrawable(
            this, com.mapbox.maps.R.drawable.mapbox_user_puck_icon
        ) ?: return
        val half = PUCK_SIZE_PX / 2f
        canvas.save()
        canvas.rotate(bearingDegrees.toFloat(), centerX, centerY)
        puckDrawable.setBounds(
            (centerX - half).toInt(),
            (centerY - half).toInt(),
            (centerX + half).toInt(),
            (centerY + half).toInt()
        )
        puckDrawable.draw(canvas)
        canvas.restore()
    }

    private fun publishMapSnapshotToLauncher(bitmap: Bitmap) {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, SNAPSHOT_JPEG_QUALITY, outputStream)
        val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        val payload = JSONObject()
            .put("channel", "map-snapshot")
            .put("data", JSONObject().put("active", true).put("bitmap", base64))
            .toString()
        Log.d(TAG, "publishMapSnapshotToLauncher: publishing | base64Length=${base64.length}")
        LauncherTurnByTurnBus.publish(payload)
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

    companion object {
        private const val SNAPSHOT_INTERVAL_MS = 3000L
        private const val SNAPSHOT_ZOOM = 16.0
        private const val SNAPSHOT_JPEG_QUALITY = 70
        // Matches HomeCardViewHolder's FOCUS_APP_WIDTH_DP/FOCUS_APP_HEIGHT_DP (613x478dp) -
        // capturing near the launcher's actual display size avoids wasted encode/decode/Binder
        // payload cost; the launcher's ImageView still centerCrops regardless.
        private const val SNAPSHOT_WIDTH_DP = 613f
        private const val SNAPSHOT_HEIGHT_DP = 478f
        // Directions API v5 default geometry precision (polyline6), matching the SDK's
        // applyDefaultNavigationOptions() route request defaults used elsewhere in the app.
        private const val ROUTE_GEOMETRY_PRECISION = 6
        private const val ROUTE_LINE_WIDTH_PX = 10f
        private const val PUCK_SIZE_PX = 64f
    }
}