package com.ivi.launcher.broadcast;

import static com.ivi.launcher.constant.LauncherConstant.HVAC_CLASS;
import static com.ivi.launcher.constant.LauncherConstant.HVAC_PACKAGE;
import static com.ivi.launcher.constant.LauncherConstant.SECOND_LAUNCHER_CLASS;
import static com.ivi.launcher.constant.LauncherConstant.SECOND_LAUNCHER_PACKAGE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.ivi.launcher.view.IviLauncherUtils;
import android.os.Handler;

public class BootBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d("BroadcastReceiver", "action: " + action);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
        if("android.intent.action.BOOT_COMPLETED".equals(action)){
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                IviLauncherUtils.startMutiDisplay(context, SECOND_LAUNCHER_PACKAGE, SECOND_LAUNCHER_CLASS, 2);
            }, 1000L);

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                IviLauncherUtils.startMutiDisplay(context, HVAC_PACKAGE, HVAC_CLASS, 3);
            }, 1000L);
        }
    }

}