package com.ivi.car.navigation.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.ivi.car.navigation.ui.MainActivity
import com.ivi.car.navigation.viewmodel.NaviViewModel

class BroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == NaviViewModel.INTENT_ACTION_FIND_NEARBY || action == NaviViewModel.INTENT_ACTION_REQUEST_ROUTE) {
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        } else if (action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed, starting MainActivity")
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        }
    }

    private companion object {
        const val TAG = "BroadcastReceiver"
    }
}