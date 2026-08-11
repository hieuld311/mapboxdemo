package com.ivi.car.navigation.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.util.Log
import com.ivi.car.navigation.INaviListener
import com.ivi.car.navigation.NaviAidlInterface
import com.ivi.car.navigation.controller.NavigationManager
import com.ivi.car.navigation.model.NavigationCommandEventType
import com.ivi.car.navigation.model.NavigationResultCode
import com.ivi.car.navigation.model.NavigationSearchResult
import com.ivi.car.navigation.model.NavigationState
import com.ivi.car.navigation.model.NavigationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NaviAidlService : Service() {
    private val listeners = RemoteCallbackList<INaviListener>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastBroadcastState: NavigationState? = null
    private var lastBroadcastAtMillis = 0L
    private var previousStatus: NavigationStatus? = null

    private val binder = object : NaviAidlInterface.Stub() {
        override fun registerListener(listener: INaviListener) {
            val registered = listeners.register(listener)
            Log.i(
                TAG,
                "registerListener: registered=$registered callbacks=${listeners.registeredCallbackCount}"
            )
            runCatching {
                listener.onNavigationStateChanged(NavigationManager.getStateJson())
            }.onFailure { error ->
                Log.w(TAG, "Initial navigation-state callback failed", error)
            }
        }

        override fun unregisterListener(listener: INaviListener) {
            val unregistered = listeners.unregister(listener)
            Log.i(
                TAG,
                "unregisterListener: unregistered=$unregistered callbacks=${listeners.registeredCallbackCount}"
            )
        }

        override fun sendNaviData(data: String) {
            Log.d(TAG, "sendNaviData from bound client: length=${data.length}")
            broadcastNaviData(data)
        }

        override fun setRoute(destination: String): Int {
            return NavigationManager.setRoute(destination)
        }

        override fun searchNearBy(category: Int, limit: Int, sortBy: Int) {
            serviceScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        NavigationManager.searchNearby(category, limit, sortBy)
                    }
                }.getOrElse { error ->
                    NavigationSearchResult(
                        resultCode = NavigationResultCode.INTERNAL_ERROR,
                        category = null,
                        sortBy = null,
                        candidates = emptyList(),
                        message = error.message ?: "Nearby search failed"
                    )
                }
                broadcastSearchNearbyResult(result.toJson())
            }
        }

        override fun selectSuggestion(suggestionId: String): Int {
            return NavigationManager.selectSuggestion(suggestionId)
        }

        override fun getNavigationState(): String {
            return NavigationManager.getStateJson()
        }

        // NAV-005 out of scope: AI Agent does not set navigation demo mode.

        override fun startNavigatingHome(): Int {
            return NavigationManager.startNavigatingHome()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        serviceScope.launch {
            NavigationManager.state.collect { state ->
                if (shouldBroadcast(state)) {
                    broadcastNavigationState(state.toJson())
                    lastBroadcastState = state
                    lastBroadcastAtMillis = System.currentTimeMillis()
                }
                if (previousStatus != NavigationStatus.ROUTE_SET &&
                    state.status == NavigationStatus.ROUTE_SET
                ) {
                    state.destination?.let { broadcastRouteChanged(it.toJson().toString()) }
                }
                previousStatus = state.status
            }
        }
        serviceScope.launch {
            NavigationManager.commandEvents.collect { event ->
                when (event.type) {
                    NavigationCommandEventType.SUCCESS -> broadcastCommandResult(event.toJson())
                    NavigationCommandEventType.ERROR -> broadcastCommandError(event.toJson())
                }
            }
        }
        serviceScope.launch {
            LauncherTurnByTurnBus.updates.collect { payload ->
                Log.d(TAG, "LauncherTurnByTurnBus update: length=${payload.length}")
                broadcastNaviData(payload)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.i(TAG, "onBind: action=${intent?.action} component=${intent?.component}")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(
            TAG,
            "onUnbind: action=${intent?.action} callbacks=${listeners.registeredCallbackCount}"
        )
        return false
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: callbacks=${listeners.registeredCallbackCount}")
        serviceScope.cancel()
        listeners.kill()
        super.onDestroy()
    }

    private fun broadcastNavigationState(stateJson: String) {
        synchronized(listeners) {
            val count = listeners.beginBroadcast()
            try {
                for (i in 0 until count) {
                    runCatching {
                        listeners.getBroadcastItem(i).onNavigationStateChanged(stateJson)
                    }
                }
            } finally {
                listeners.finishBroadcast()
            }
        }
    }

    private fun broadcastNaviData(data: String) {
        synchronized(listeners) {
            val count = listeners.beginBroadcast()
            Log.d(TAG, "broadcastNaviData: listenerCount=$count length=${data.length}")
            try {
                for (i in 0 until count) {
                    try {
                        listeners.getBroadcastItem(i).onNaviDataReceived(data)
                    } catch (e: RemoteException) {
                        Log.w(TAG, "onNaviDataReceived callback failed for listener #$i", e)
                    }
                }
            } finally {
                listeners.finishBroadcast()
            }
        }
    }

    private fun broadcastCommandResult(data: String) {
        synchronized(listeners) {
            val count = listeners.beginBroadcast()
            try {
                for (i in 0 until count) {
                    runCatching { listeners.getBroadcastItem(i).onCommandResult(data) }
                }
            } finally {
                listeners.finishBroadcast()
            }
        }
    }

    private fun broadcastCommandError(data: String) {
        synchronized(listeners) {
            val count = listeners.beginBroadcast()
            try {
                for (i in 0 until count) {
                    runCatching { listeners.getBroadcastItem(i).onCommandError(data) }
                }
            } finally {
                listeners.finishBroadcast()
            }
        }
    }

    private fun broadcastRouteChanged(data: String) {
        synchronized(listeners) {
            val count = listeners.beginBroadcast()
            try {
                for (i in 0 until count) {
                    runCatching { listeners.getBroadcastItem(i).onRouteChanged(data) }
                }
            } finally {
                listeners.finishBroadcast()
            }
        }
    }

    private fun broadcastSearchNearbyResult(data: String) {
        synchronized(listeners) {
            val count = listeners.beginBroadcast()
            try {
                for (i in 0 until count) {
                    runCatching { listeners.getBroadcastItem(i).onSearchNearbyResult(data) }
                }
            } finally {
                listeners.finishBroadcast()
            }
        }
    }

    private fun shouldBroadcast(state: NavigationState): Boolean {
        val previous = lastBroadcastState ?: return true
        val structuralChange =
            previous.status != state.status ||
                    previous.destination?.suggestionId != state.destination?.suggestionId ||
                    previous.suggestions.map { it.suggestionId } !=
                    state.suggestions.map { it.suggestionId } ||
                    previous.mapStyle != state.mapStyle ||
                    previous.demoMode != state.demoMode ||
                    previous.message != state.message
        return structuralChange ||
                System.currentTimeMillis() - lastBroadcastAtMillis >= PROGRESS_BROADCAST_INTERVAL_MILLIS
    }

    companion object {
        private const val TAG = "NaviAidlService"
        private const val PROGRESS_BROADCAST_INTERVAL_MILLIS = 500L
    }
}
