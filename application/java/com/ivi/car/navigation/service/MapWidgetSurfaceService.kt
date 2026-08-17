package com.ivi.car.navigation.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.window.SurfaceControlViewHost
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewTreeLifecycleOwner
import com.ivi.car.navigation.MapWidgetSurfaceInterface
import com.ivi.car.navigation.R
import com.ivi.car.navigation.controller.NavigationManager
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * New, isolated service: hosts a live, read-only MapView per connected widget client via
 * SurfaceControlViewHost (API 32+ only), and hands the resulting SurfacePackage back over
 * Binder for embedding in a remote SurfaceView (e.g. the launcher's home card).
 *
 * Camera is a read-only mirror of NavigationManager.widgetCamera (itself fed only by
 * NaviFragment, see NaviFragment.locationObserver) — so the widget matches whatever NaviFragment
 * is actually rendering, real GPS or simulated replay alike. This file does not read or modify
 * NaviAidlService, or any of NaviFragment's own camera/route-line logic.
 */
class MapWidgetSurfaceService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessions = ConcurrentHashMap<IBinder, WidgetMapSession>()

    // Mapbox's MapView requires a LifecycleOwner reachable from its view tree on attach
    // (ViewLifecycleOwner.doOnAttached throws otherwise) — there's no Activity/Fragment here,
    // so each session carries its own minimal, manually-driven one.
    private class SessionLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun handleLifecycleEvent(event: Lifecycle.Event) = registry.handleLifecycleEvent(event)
    }

    private inner class WidgetMapSession(val clientToken: IBinder) {
        val lifecycleOwner = SessionLifecycleOwner()
        val mapView: MapView = MapView(themedContext())
        var host: SurfaceControlViewHost? = null
        var cameraJob: Job? = null
        val deathRecipient = IBinder.DeathRecipient {
            Log.w(TAG, "Widget client died without releasing; cleaning up")
            mainHandler.post { release(clientToken) }
        }
    }

    private val binder = object : MapWidgetSurfaceInterface.Stub() {
        override fun requestMapSurface(
            hostToken: IBinder,
            displayId: Int,
            widthPx: Int,
            heightPx: Int,
            clientToken: IBinder
        ): Bundle {
            if (Build.VERSION.SDK_INT < 32) {
                Log.w(TAG, "requestMapSurface ignored: requires API 32+, running ${Build.VERSION.SDK_INT}")
                return Bundle()
            }
            if (widthPx <= 0 || heightPx <= 0) {
                Log.w(TAG, "requestMapSurface ignored: invalid size ${widthPx}x$heightPx")
                return Bundle()
            }

            val result = Bundle()
            val latch = CountDownLatch(1)
            mainHandler.post {
                try {
                    val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                        .getDisplay(displayId)
                    if (display == null) {
                        Log.w(TAG, "requestMapSurface: no display for id=$displayId")
                        return@post
                    }

                    // Idempotent: a stale session for the same client (e.g. re-request after
                    // layout change) is torn down before creating a fresh one.
                    release(clientToken)

                    val session = WidgetMapSession(clientToken)
                    try {
                        clientToken.linkToDeath(session.deathRecipient, 0)
                    } catch (error: Exception) {
                        Log.w(TAG, "linkToDeath failed", error)
                    }
                    sessions[clientToken] = session

                    // Must be set before the MapView is ever attached to a window: Mapbox's
                    // attach-time lifecycle lookup runs synchronously inside setView() below.
                    session.lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                    ViewTreeLifecycleOwner.set(session.mapView, session.lifecycleOwner)

                    setupMapView(session.mapView)

                    val viewHost = SurfaceControlViewHost(themedContext(), display, hostToken)
                    viewHost.setView(session.mapView, widthPx, heightPx)
                    session.host = viewHost

                    session.lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
                    session.lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

                    session.cameraJob = serviceScope.launch {
                        NavigationManager.widgetCamera.filterNotNull().collectLatest { snapshot ->
                            session.mapView.camera.easeTo(
                                CameraOptions.Builder()
                                    .center(snapshot.center)
                                    .zoom(snapshot.zoom)
                                    .bearing(snapshot.bearing)
                                    .pitch(snapshot.pitch)
                                    .build(),
                                MapAnimationOptions.Builder()
                                    .duration(CAMERA_EASE_DURATION_MS)
                                    .build()
                            )
                        }
                    }

                    viewHost.surfacePackage?.let { pkg ->
                        result.putParcelable(MapWidgetSurfaceInterface.KEY_SURFACE_PACKAGE, pkg)
                    } ?: Log.w(TAG, "requestMapSurface: surfacePackage was null after setView")
                } catch (error: Exception) {
                    Log.e(TAG, "requestMapSurface failed", error)
                } finally {
                    latch.countDown()
                }
            }
            latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            return result
        }

        override fun releaseMapSurface(clientToken: IBinder) {
            mainHandler.post { release(clientToken) }
        }
    }

    private fun themedContext(): Context =
        ContextThemeWrapper(applicationContext, R.style.Theme_Navigation)

    private fun setupMapView(mapView: MapView) {
        mapView.mapboxMap.loadStyle(Style.MAPBOX_STREETS) { }
        // Device-GPS puck stays on for visual reference; camera-follow is driven separately by
        // the NavigationManager.widgetCamera collector above, not by this puck's own position.
        mapView.location.updateSettings {
            enabled = true
            pulsingEnabled = false
            puckBearing = PuckBearing.HEADING
            locationPuck = createDefault2DPuck(withBearing = true)
        }
    }

    // Main-thread only: called both from the Binder-thread request path (posted) and onDestroy.
    private fun release(clientToken: IBinder) {
        val session = sessions.remove(clientToken) ?: return
        try {
            clientToken.unlinkToDeath(session.deathRecipient, 0)
        } catch (error: Exception) {
            // Already unlinked or binder already dead; safe to ignore.
        }
        session.cameraJob?.cancel()
        session.lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        session.lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        session.host?.release()
        session.mapView.onStop()
        session.mapView.onDestroy()
        session.lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind: action=${intent?.action}")
        return binder
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: releasing ${sessions.size} widget map session(s)")
        mainHandler.post {
            for (clientToken in sessions.keys.toList()) {
                release(clientToken)
            }
            serviceScope.cancel()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MapWidgetSurfaceService"
        private const val REQUEST_TIMEOUT_SECONDS = 2L
        private const val CAMERA_EASE_DURATION_MS = 750L
    }
}
