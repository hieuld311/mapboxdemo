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
    companion object {
        private const val TAG = "NaviAidlService"
    }

    private val listeners = RemoteCallbackList<INaviListener>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastBroadcastState: NavigationState? = null
    private var lastBroadcastAtMillis = 0L
    private var previousStatus: NavigationStatus? = null

    private val binder = object : NaviAidlInterface.Stub() {
        override fun registerListener(listener: INaviListener) {
            listeners.register(listener)
            Log.i(TAG, "registerListener: total listeners = ${listeners.registeredCallbackCount}")
            runCatching {
                listener.onNavigationStateChanged(NavigationManager.getStateJson())
            }
        }

        override fun unregisterListener(listener: INaviListener) {
            listeners.unregister(listener)
            Log.i(TAG, "unregisterListener: total listeners = ${listeners.registeredCallbackCount}")
        }

        override fun sendNaviData(data: String) {
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

        override fun setNavigationDemoMode(mode: Int): Int {
            return NavigationManager.setNavigationDemoMode(mode)
        }

        override fun startNavigatingHome(): Int {
            return NavigationManager.startNavigatingHome()
        }
    }

    override fun onCreate() {
        super.onCreate()
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
                Log.i(TAG, "LauncherTurnByTurnBus payload received: $payload")
                broadcastNaviData(payload)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onDestroy() {
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
            Log.i(TAG, "broadcastNaviData: broadcasting to $count listener(s)")
            try {
                for (i in 0 until count) {
                    try {
                        listeners.getBroadcastItem(i).onNaviDataReceived(data)
                    } catch (e: RemoteException) {
                        Log.w(TAG, "broadcastNaviData: listener $i failed", e)
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
        private const val PROGRESS_BROADCAST_INTERVAL_MILLIS = 500L
    }
}