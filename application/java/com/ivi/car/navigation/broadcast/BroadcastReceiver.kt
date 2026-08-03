package com.ivi.car.navigation.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.ivi.car.navigation.viewmodel.NaviViewModel

class BroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == NaviViewModel.INTENT_ACTION_FIND_NEARBY || action == NaviViewModel.INTENT_ACTION_REQUEST_ROUTE) {
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }
    }
}