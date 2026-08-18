package com.ivi.launcher.model.home;

import android.content.Context;

import androidx.annotation.NonNull;

public class HomeDisplayPolicyProvider {
    private final Context mContext;
    private boolean mVideoWidgetAllowed = true;

    public HomeDisplayPolicyProvider(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    public boolean isVideoWidgetAllowed() {
        return mVideoWidgetAllowed;
    }

    public void setVideoWidgetAllowedForDebug(boolean allowed) {
        mVideoWidgetAllowed = allowed;
    }
}