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
import com.mapbox.navigation.base.trip.model.RouteProgressState
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationProvider
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
        if(!MainActivity.isRunning && canUpdate) {
            sendNaviData()
        }

        if (routeProgress.currentState == RouteProgressState.COMPLETE) {
            NavigationManager.stopNavigation()
            if (!MainActivity.isRunning) {
                navigation = Navigation()
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

    private fun unregisterObserver() {
        if (::mapboxNavigation.isInitialized) {
            if (routeObserverRegistered) {
                mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
                routeObserverRegistered = false
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
}
