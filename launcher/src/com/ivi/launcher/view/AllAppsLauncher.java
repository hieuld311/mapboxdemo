/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.github.penfeizhou.animation.apng.APNGDrawable;
import com.ivi.launcher.R;

public class AllAppsLauncher extends AppCompatActivity {
    private static final String TAG = "AllAppsLauncher";
    private static final String HOME_BACKGROUND_APNG = "cob_home_12fps.png";

    private static final String VIDEO_PACKAGE = "com.ivi.video.cid";
    private static final String VIDEO_ACTIVITY = "com.ivi.cid.ui.VideoLibraryActivity";

    private static final String MUSIC_PACKAGE = "com.ivi.media.cid";
    private static final String MUSIC_ACTIVITY = "com.ivi.media.MainActivity";

    private static final String PHONE_PACKAGE = "com.android.car.dialer";

    private static final String VEHICLE_INFO_PACKAGE = "com.ivi.vehicleinfo";
    private static final String VEHICLE_INFO_ACTIVITY = "com.ivi.vehicleinfo.VehicleInfoMainActivity";

    private static final String NAVIGATION_PACKAGE = "com.ivi.car.navigation";
    private static final String NAVIGATION_ACTIVITY = "com.ivi.car.navigation.ui.MainActivity";

    // TODO: Fill these two apps when package/activity names are confirmed.
    private static final String CLIMATE_PACKAGE = "";
    private static final String CLIMATE_ACTIVITY = "";
    private static final String MEETING_PACKAGE = "com.fauto.meetingapp.cid";
    private static final String MEETING_ACTIVITY = "com.meeting.ui.activity.MainActivity";

    @Nullable
    private ImageView homeBG;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureLauncherWindow();
        setContentView(R.layout.all_apps_launcher);
        homeBG = findViewById(R.id.homeBG);
        setApngDrawable(homeBG, HOME_BACKGROUND_APNG, false);
        bindAllAppCards();
    }

    private void configureLauncherWindow() {
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        window.getDecorView().setBackgroundColor(Color.BLACK);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        // NOTE: Only make system bars transparent and let content draw edge-to-edge.
        // Do NOT hide status/navigation bars here (previously caused status bar to
        // disappear on the All Apps screen). Keep behavior consistent with
        // IviLauncher#forceTransparentSystemBars().
        int systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        window.getDecorView().setSystemUiVisibility(systemUiVisibility);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attrs);
        }
    }

    private void setApngDrawable(@Nullable ImageView imageView, @NonNull String assetName, boolean freeze) {
        if (imageView == null) {
            Log.w(TAG, "setApngDrawable skipped because target ImageView is null, asset=" + assetName);
            return;
        }
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        try {
            APNGDrawable apngDrawable = APNGDrawable.fromAsset(getApplicationContext(), assetName);
            if (freeze) {
                apngDrawable.setLoopLimit(1);
            }
            imageView.setImageDrawable(apngDrawable);
            Log.d(TAG, "setApngDrawable success asset=" + assetName + ", freeze=" + freeze);
        } catch (Throwable throwable) {
            Log.e(TAG, "setApngDrawable failed asset=" + assetName, throwable);
            imageView.setImageDrawable(null);
            imageView.setBackgroundColor(Color.BLACK);
        }
    }

    private void bindAllAppCards() {
        bindLaunchClick(R.id.videoButton, VIDEO_PACKAGE, VIDEO_ACTIVITY);
        bindLaunchClick(R.id.musicButton, MUSIC_PACKAGE, MUSIC_ACTIVITY);
        bindLaunchClick(R.id.phoneButton, PHONE_PACKAGE, null);
        bindLaunchClick(R.id.vehicleInfoButton, VEHICLE_INFO_PACKAGE, VEHICLE_INFO_ACTIVITY);
        bindLaunchClick(R.id.navigationButton, NAVIGATION_PACKAGE, NAVIGATION_ACTIVITY);
        bindLaunchClick(R.id.climateButton, CLIMATE_PACKAGE, CLIMATE_ACTIVITY);
        bindLaunchClick(R.id.meetingButton, MEETING_PACKAGE, MEETING_ACTIVITY);
    }

    private void bindLaunchClick(
            @IdRes int viewId,
            @Nullable String packageName,
            @Nullable String activityName
    ) {
        View view = findViewById(viewId);
        if (view == null) {
            Log.w(TAG, "bindLaunchClick: view not found, id=" + viewId);
            return;
        }
        view.setOnClickListener(v -> launchApp(packageName, activityName));
    }

    private void launchApp(@Nullable String packageName, @Nullable String activityName) {
        if (isEmpty(packageName)) {
            Log.w(TAG, "launchApp ignored: package is not configured yet");
            return;
        }

        Intent intent;
        if (!isEmpty(activityName)) {
            intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            intent.setComponent(new ComponentName(packageName, activityName));
        } else {
            intent = getPackageManager().getLaunchIntentForPackage(packageName);
        }

        if (intent == null) {
            Log.w(TAG, "launchApp ignored: no launch intent for package=" + packageName);
            return;
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "launchApp failed: package=" + packageName
                    + ", activity=" + activityName, e);
        }
    }

    private boolean isEmpty(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }
}
