/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ivi.launcher.view;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.util.Log;
import android.app.ActivityOptions;

import com.ivi.launcher.R;
import com.ivi.launcher.constant.LauncherConstant;

import java.net.URISyntaxException;

/**
 * Utils for CarLauncher package.
 */
public class IviLauncherUtils {

    private static final String TAG = "IviLauncherUtils";

    private IviLauncherUtils() {
    }

    /** Intent used to find/launch the maps activity to run in the relevant DisplayArea. */
    public static Intent getMapsIntent() {
        Intent mapIntent = new Intent();
        mapIntent.setComponent(new ComponentName(LauncherConstant.PKG_MAPS, LauncherConstant.CLS_MAPS));
        return mapIntent;
    }

    /** Intent used to find/launch the dialer activity to run in the relevant DisplayArea. */
    public static Intent getDialerIntent() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(LauncherConstant.PKG_DIALER, LauncherConstant.CLS_DIALER));
        return intent;
    }

    /** Intent used to find/launch the media activity to run in the relevant DisplayArea. */
    public static Intent getMediaIntent() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(LauncherConstant.PKG_MEDIA, LauncherConstant.CLS_MEDIA));
        return intent;
    }

    /**
     * Returns {@code true} if a proper limited map intent is configured via
     * {@code config_smallCanvasOptimizedMapIntent} string resource.
     */
    public static boolean isSmallCanvasOptimizedMapIntentConfigured(Context context) {
        String intentString = context.getString(R.string.config_smallCanvasOptimizedMapIntent);
        if (intentString.isEmpty()) {
            return false;
        }

        try {
            Intent.parseUri(intentString, Intent.URI_INTENT_SCHEME);
            return true;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Returns an intent to trigger a map with a limited functionality (e.g., one to be used when
     * there's not much screen real estate).
     */
    public static Intent getSmallCanvasOptimizedMapIntent(Context context) {
        String intentString = context.getString(R.string.config_smallCanvasOptimizedMapIntent);
        try {
            Intent intent = Intent.parseUri(intentString, Intent.URI_INTENT_SCHEME);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (URISyntaxException e) {
            Log.w(TAG, "Invalid intent URI in config_smallCanvasOptimizedMapIntent: \""
                    + intentString + "\". Falling back to fullscreen map.");
            return getMapsIntent();
        }
    }

    static boolean isCustomDisplayPolicyDefined(Context context) {
        Resources resources = context.getResources();
        String customPolicyName = null;
        try {
            customPolicyName = resources
                    .getString(
                            com.android.internal
                                    .R.string.config_deviceSpecificDisplayAreaPolicyProvider);
        } catch (Resources.NotFoundException ex) {
            Log.w(TAG, "custom policy provider not defined");
        }
        return customPolicyName != null && !customPolicyName.isEmpty();
    }

    public static void startMutiDisplay(Context context, String packageName, String className, int displayId) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(packageName, className));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);

            context.startActivity(intent, options.toBundle());
            Log.i(TAG, "Successfully launched " + className + " on display " + displayId);

        } catch (SecurityException e) {
            Log.e(TAG, "Security exception, can't start activity:" + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Can't start activity with exception: " + e.getMessage());
        }
    }
}
