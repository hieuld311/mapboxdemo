package com.ivi.car.navigation.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.SurfaceControlViewHost
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.ivi.car.navigation.MapWidgetSurfaceInterface
import com.ivi.car.navigation.R
import com.ivi.car.navigation.controller.NavigationManager
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.TimeFormat
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.formatter.MapboxDistanceFormatter
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import com.mapbox.navigation.tripdata.progress.api.MapboxTripProgressApi
import com.mapbox.navigation.tripdata.progress.model.DistanceRemainingFormatter
import com.mapbox.navigation.tripdata.progress.model.EstimatedTimeToArrivalFormatter
import com.mapbox.navigation.tripdata.progress.model.PercentDistanceTraveledFormatter
import com.mapbox.navigation.tripdata.progress.model.TimeRemainingFormatter
import com.mapbox.navigation.tripdata.progress.model.TripProgressUpdateFormatter
import com.mapbox.navigation.ui.components.maneuver.view.MapboxManeuverView
import com.mapbox.navigation.ui.components.tripprogress.view.MapboxTripProgressView
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
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
 * Camera and puck are a read-only mirror of NavigationManager.widgetCamera /
 * widgetLocationMatcherResult (both fed only by NaviFragment, see NaviFragment.locationObserver)
 * - so the widget always matches whatever NaviFragment is actually rendering, real GPS or
 * simulated replay alike, and stays live (map + puck) even when idle. The maneuver + trip
 * progress card mirrors NavigationManager.widgetRouteProgress and is visible ONLY while a route
 * is active (null progress = idle = card hidden, map + puck only). This file does not read or
 * modify NaviAidlService, or any of NaviFragment's own camera/route-line logic.
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
        val routeLineApi = MapboxRouteLineApi(
            MapboxRouteLineApiOptions.Builder().vanishingRouteLineEnabled(true).build()
        )
        val routeLineView = MapboxRouteLineView(
            MapboxRouteLineViewOptions.Builder(themedContext()).build()
        )
        val locationProvider = NavigationLocationProvider()
        // Vanishing route line (trims the already-traveled portion behind the puck), matching
        // NaviFragment's own onPositionChangedListener exactly. registered/removed against
        // mapView.location in setupMapView()/release() below.
        val onPositionChangedListener = OnIndicatorPositionChangedListener { point ->
            val result = routeLineApi.updateTraveledRouteLine(point)
            mapView.mapboxMap.style?.apply {
                routeLineView.renderRouteLineUpdate(this, result)
            }
        }
        val maneuverView: MapboxManeuverView = MapboxManeuverView(themedContext())
        val tripProgressView: MapboxTripProgressView = MapboxTripProgressView(themedContext())
        val maneuverApi = MapboxManeuverApi(
            MapboxDistanceFormatter(DistanceFormatterOptions.Builder(themedContext()).build())
        )
        val tripProgressApi = MapboxTripProgressApi(
            TripProgressUpdateFormatter.Builder(themedContext())
                .distanceRemainingFormatter(
                    DistanceRemainingFormatter(
                        DistanceFormatterOptions.Builder(themedContext()).build()
                    )
                )
                .timeRemainingFormatter(TimeRemainingFormatter(themedContext()))
                .percentRouteTraveledFormatter(PercentDistanceTraveledFormatter())
                .estimatedTimeToArrivalFormatter(
                    EstimatedTimeToArrivalFormatter(themedContext(), TimeFormat.NONE_SPECIFIED)
                )
                .build()
        )
        // Rounded, translucent card docked top-left: maneuverView above a 1dp divider above
        // tripProgressView, stacked vertically. Built in buildCompositeRoot(), GONE until the
        // first non-null widgetRouteProgress arrives (see progressJob below).
        var progressCard: LinearLayout? = null
        var host: SurfaceControlViewHost? = null
        var cameraJob: Job? = null
        var routesJob: Job? = null
        var locationJob: Job? = null
        var progressJob: Job? = null
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

                    // Must be set before the composite root is ever attached to a window:
                    // Mapbox's attach-time lifecycle lookup (inside the child MapView) runs
                    // synchronously as soon as the root is attached via viewHost.setView() below,
                    // and ViewTreeLifecycleOwner resolves by walking up the parent chain - so
                    // setting it on the composite root (before setView) is sufficient for the
                    // child MapView to find it too.
                    session.lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                    val compositeRoot = buildCompositeRoot(session)
                    compositeRoot.setViewTreeLifecycleOwner(session.lifecycleOwner)
                    setupMapView(session)

                    val viewHost = SurfaceControlViewHost(themedContext(), display, hostToken)
                    viewHost.setView(compositeRoot, widthPx, heightPx)
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

                    session.routesJob = serviceScope.launch {
                        NavigationManager.widgetRoutes.collectLatest { routes ->
                            val style = session.mapView.mapboxMap.style ?: return@collectLatest
                            if (routes.isNotEmpty()) {
                                session.routeLineApi.setNavigationRoutes(routes) { value ->
                                    session.routeLineView.renderRouteDrawData(style, value)
                                }
                            } else {
                                session.routeLineApi.clearRouteLine { value ->
                                    session.routeLineView.renderClearRouteLineValue(style, value)
                                }
                            }
                        }
                    }

                    session.locationJob = serviceScope.launch {
                        NavigationManager.widgetLocationMatcherResult.filterNotNull()
                            .collectLatest { locationMatcherResult ->
                                session.locationProvider.changePosition(
                                    location = locationMatcherResult.enhancedLocation,
                                    keyPoints = locationMatcherResult.keyPoints
                                )
                            }
                    }

                    session.progressJob = serviceScope.launch {
                        NavigationManager.widgetRouteProgress.collectLatest { routeProgress ->
                            val card = session.progressCard ?: return@collectLatest
                            if (routeProgress == null) {
                                card.visibility = View.GONE
                                return@collectLatest
                            }
                            card.visibility = View.VISIBLE
                            val maneuvers = session.maneuverApi.getManeuvers(routeProgress)
                            session.maneuverView.renderManeuvers(maneuvers)
                            session.tripProgressView.render(
                                session.tripProgressApi.getTripProgress(routeProgress)
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

    /**
     * MapView (full-bleed) with the maneuver+divider+tripProgress card overlaid top-left. The
     * card starts GONE; progressJob (started right after this) toggles it based on
     * NavigationManager.widgetRouteProgress, per the idle=map+puck-only / active=card-visible
     * requirement.
     */
    private fun buildCompositeRoot(session: WidgetMapSession): FrameLayout {
        val root = FrameLayout(themedContext())
        root.addView(
            session.mapView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val cardBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPxF(CARD_CORNER_RADIUS_DP)
            setColor(CARD_BACKGROUND_COLOR)
        }
        val progressCard = LinearLayout(themedContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground
            visibility = View.GONE
        }
        progressCard.addView(
            session.maneuverView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        val divider = View(themedContext()).apply {
            setBackgroundColor(CARD_DIVIDER_COLOR)
        }
        progressCard.addView(
            divider,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(CARD_DIVIDER_HEIGHT_DP))
        )
        progressCard.addView(
            session.tripProgressView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        val cardParams = FrameLayout.LayoutParams(
            dpToPx(CARD_WIDTH_DP),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(dpToPx(CARD_MARGIN_DP), dpToPx(CARD_MARGIN_DP), dpToPx(CARD_MARGIN_DP), dpToPx(CARD_MARGIN_DP))
        }
        root.addView(progressCard, cardParams)
        session.progressCard = progressCard

        return root
    }

    private fun dpToPx(dp: Int): Int = dpToPxF(dp).toInt()

    private fun dpToPxF(dp: Int): Float = dp * themedContext().resources.displayMetrics.density

    private fun setupMapView(session: WidgetMapSession) {
        // Puck position comes exclusively from NavigationManager.widgetLocationMatcherResult
        // (locationJob above), which mirrors whatever NaviFragment is actually rendering - no
        // independent device-GPS lookup here, matching NaviFragment's own puck setup.
        session.mapView.location.apply {
            setLocationProvider(session.locationProvider)
            addOnIndicatorPositionChangedListener(session.onPositionChangedListener)
            puckBearingEnabled = true
            showAccuracyRing = true
            enabled = true
            puckBearing = PuckBearing.COURSE
            // Matches NaviFragment's own puck icons exactly (see its initView()/
            // updateVehiclePuck()) - the default createDefault2DPuck() icon on its own renders
            // noticeably smaller than what the in-app map shows.
            locationPuck = createDefault2DPuck(true).apply {
                topImage = ImageHolder.from(com.mapbox.maps.R.drawable.mapbox_user_puck_icon)
                bearingImage = ImageHolder.from(com.mapbox.maps.R.drawable.mapbox_user_puck_icon)
                shadowImage = ImageHolder.from(com.mapbox.maps.R.drawable.mapbox_user_icon_shadow)
            }
        }

        session.mapView.mapboxMap.loadStyle(Style.MAPBOX_STREETS) { style ->
            session.routeLineView.initializeLayers(style)
            // The routesJob collector (started right after this call) handles every route
            // change from here on, but it's a StateFlow with no guaranteed ordering against
            // this async style load — render whatever's already current now, so a widget
            // attached mid-navigation shows the existing route immediately instead of waiting
            // for the next route change.
            val currentRoutes: List<NavigationRoute> = NavigationManager.widgetRoutes.value
            if (currentRoutes.isNotEmpty()) {
                session.routeLineApi.setNavigationRoutes(currentRoutes) { value ->
                    session.routeLineView.renderRouteDrawData(style, value)
                }
            }
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
        session.routesJob?.cancel()
        session.locationJob?.cancel()
        session.progressJob?.cancel()
        session.routeLineApi.cancel()
        session.mapView.location.removeOnIndicatorPositionChangedListener(session.onPositionChangedListener)
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
        private const val CARD_MARGIN_DP = 12
        private const val CARD_WIDTH_DP = 200
        private const val CARD_CORNER_RADIUS_DP = 12
        private const val CARD_DIVIDER_HEIGHT_DP = 1
        private val CARD_BACKGROUND_COLOR = Color.parseColor("#99202020")
        private val CARD_DIVIDER_COLOR = Color.parseColor("#33FFFFFF")
    }
}
