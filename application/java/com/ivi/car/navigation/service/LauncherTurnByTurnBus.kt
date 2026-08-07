package com.ivi.car.navigation.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-local bridge from the Mapbox foreground service to the exported
 * navigation AIDL service. The launcher receives these values through its
 * existing INaviListener callback.
 */
object LauncherTurnByTurnBus {
    private val mutableUpdates = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val updates = mutableUpdates.asSharedFlow()

    fun publish(payload: String) {
        mutableUpdates.tryEmit(payload)
    }
}
