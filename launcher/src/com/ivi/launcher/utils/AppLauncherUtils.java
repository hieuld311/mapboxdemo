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

package com.ivi.launcher.utils;

import android.app.ActivityManager;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.content.pm.CarPackageManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Process;
import android.service.media.MediaBrowserService;
import android.util.Log;
import android.widget.Toast;
import android.app.WindowConfiguration;

import androidx.annotation.NonNull;

import com.ivi.launcher.R;
import com.ivi.launcher.constant.LauncherConstant;
import com.ivi.launcher.model.AppMetaData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Util class that contains helper method used by app launcher classes.
 */
public class AppLauncherUtils {

    private static final String TAG = "AppLauncherUtils";

    private AppLauncherUtils() {
    }

    /**
     * Comparator for {@link AppMetaData} that sorts the list
     * by the "displayName" property in ascending order.
     */
    public static final Comparator<AppMetaData> ALPHABETICAL_COMPARATOR = Comparator.comparing(AppMetaData::getDisplayName, String::compareToIgnoreCase);

    /**
     * Helper method that launches the app given the app's AppMetaData.
     *
     * @param app the requesting app's AppMetaData
     */
    public static void launchApp(Context context, AppMetaData app) {
//        new Handler().postDelayed(new Runnable() {
//            @Override
//            public void run() {
        String packageName = app.getPackageName();
        Log.d(TAG, "Launcher launchApp with package: " + packageName);

        if (packageName.equals(LauncherConstant.PKG_FAKE_HERE)) {
            String herePackage = LauncherConstant.PKG_AUTH_HERE;

            Log.e(TAG, "launchApp HERE Nav: " + herePackage + " for " + packageName + " is only used for app icon");

            PackageManager pm = context.getPackageManager();
            Intent intentActivity;
            intentActivity = pm.getLaunchIntentForPackage(herePackage);
            intentActivity.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intentActivity);
        } else {
            Log.d(TAG, "Launcher startActivity: " + packageName);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(context.getDisplayId());
            //        ActivityManager.StackInfo stackInfo = SecondLauncherController.getRunningTaskOnSecondaryScreen();
            //
            //        if (stackInfo != null && stackInfo.topActivity.getPackageName().equals(app.getPackageName())) {
            //            Toast.makeText(context, "The " + app.getDisplayName() + " app is being launched on the secondary screen", Toast.LENGTH_LONG).show();
            //        } else {
            context.startActivity(app.getMainLaunchIntent(), options.toBundle());
            //        }
        }
    }
//        }, 500);
//    }

    /**
     * Bundles application and services info.
     */
    public static class LauncherAppsInfo {
        /**
         * Map of all apps' metadata keyed by package name.
         */
        private final Map<String, AppMetaData> mApplications;

        /**
         * Map of all the media services keyed by package name.
         */
//        private final Map<String, ResolveInfo> mMediaServices;

        LauncherAppsInfo(@NonNull Map<String, AppMetaData> apps) {
            mApplications = apps;
//            mMediaServices = mediaServices;
        }

        /**
         * Returns true if all maps are empty.
         */
        public boolean isEmpty() {
            return mApplications.isEmpty();
        }

        /**
         * Returns whether the given package name is a media service.
         */
        public boolean isMediaService(String packageName) {
            return true;
        }

        /**
         * Returns the {@link AppMetaData} for the given package name.
         */
        @Nullable
        public AppMetaData getAppMetaData(String packageName) {
            return mApplications.get(packageName);
        }

        /**
         * Returns a new list of the applications' {@link AppMetaData}.
         */
        @NonNull
        public List<AppMetaData> getApplicationsList() {
            return new ArrayList<>(mApplications.values());
        }
    }

    private final static LauncherAppsInfo EMPTY_APPS_INFO = new LauncherAppsInfo(Collections.emptyMap());

    /**
     * Gets all the apps that we want to see in the launcher in unsorted order. Includes media
     * services without launcher activities.
     *
     * @param blackList         A (possibly empty) list of apps to hide
     * @param launcherApps      The {@link LauncherApps} system service
     * @param carPackageManager The {@link CarPackageManager} system service
     * @param packageManager    The {@link PackageManager} system service
     * @return a new {@link LauncherAppsInfo}
     */
    @NonNull
    public static LauncherAppsInfo getAllLauncherApps(@NonNull Set<String> blackList, LauncherApps launcherApps, CarPackageManager carPackageManager, PackageManager packageManager) {

        if (launcherApps == null || carPackageManager == null || packageManager == null) {
            return EMPTY_APPS_INFO;
        }
        List<LauncherActivityInfo> availableActivities = launcherApps.getActivityList(null, Process.myUserHandle());

        Map<String, AppMetaData> apps = new HashMap<>(availableActivities.size());

        // Process activities
        for (LauncherActivityInfo info : availableActivities) {
            String packageName = info.getComponentName().getPackageName();
            if (shouldAdd(packageName, apps, blackList)) {
                boolean isDistractionOptimized = isActivityDistractionOptimized(carPackageManager, packageName, info.getName());

                AppMetaData appMetaData = new AppMetaData(getCustomAppName(packageName, info.getLabel()), packageName, info.getBadgedIcon(0), isDistractionOptimized, packageManager.getLaunchIntentForPackage(packageName), null);
                apps.put(packageName, appMetaData);
            }
        }

        return new LauncherAppsInfo(apps);
    }

    private static boolean shouldAdd(String packageName, Map<String, AppMetaData> apps, @NonNull Set<String> blackList) {
        return !apps.containsKey(packageName) && !blackList.contains(packageName);
    }

    /**
     * Gets if an activity is distraction optimized.
     *
     * @param carPackageManager The {@link CarPackageManager} system service
     * @param packageName       The package name of the app
     * @param activityName      The requested activity name
     * @return true if the supplied activity is distraction optimized
     */
    static boolean isActivityDistractionOptimized(CarPackageManager carPackageManager, String packageName, String activityName) {
        boolean isDistractionOptimized = false;
        // try getting distraction optimization info
        try {
            if (carPackageManager != null) {
                isDistractionOptimized = carPackageManager.isActivityDistractionOptimized(packageName, activityName);
            }
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car not connected when getting DO info", e);
        }
        return isDistractionOptimized;
    }

    public static int getAppIconResource(@NonNull String packageName) {
        Integer resId = APP_ICON_MAP.get(normalizePackageName(packageName));
        return resId != null ? resId : -1;
    }

    public static String getCustomAppName(@NonNull String packageName, CharSequence defaultName) {
        return APP_NAME_MAP.getOrDefault(normalizePackageName(packageName),
                String.valueOf(defaultName));
    }

    private static String normalizePackageName(String packageName) {
        return packageName == null ? "" : packageName.trim().toLowerCase();
    }
    
    private static final Map<String, Integer> APP_ICON_MAP;

    private static final Map<String, String> APP_NAME_MAP;

    static {
        Map<String, Integer> iconMap = new HashMap<>();
        iconMap.put("com.android.car.calendar1", R.drawable.ico_laucher_list_app_common_calendar);
        iconMap.put("com.ivi.car.dms", R.drawable.ico_laucher_list_app_common_dms);
        iconMap.put("com.fauto.diagnostic", R.drawable.ico_laucher_list_app_common_performance);
        iconMap.put("com.fauto.carprofile", R.drawable.ico_laucher_list_app_common_profile);
        iconMap.put("com.ivi.media", R.drawable.ico_laucher_list_app_common_media);
        iconMap.put("com.android.car.dialer", R.drawable.ico_laucher_list_app_common_phone);
        iconMap.put("com.ivi.car.carmode", R.drawable.ico_laucher_list_app_common_carmode);
        iconMap.put("com.hrap.app.memo", R.drawable.ico_laucher_list_app_common_memo);
        iconMap.put("com.android.car.radio", R.drawable.ico_laucher_list_app_common_radio);
        iconMap.put("com.android.car.settings", R.drawable.ico_laucher_list_app_common_setup);
        iconMap.put("com.android.car.messenger", R.drawable.ico_laucher_list_app_common_sms);
        iconMap.put("com.ivi.weatherapp", R.drawable.ico_laucher_list_app_common_weather);
        iconMap.put("com.fauto.car.smarthome", R.drawable.ico_laucher_list_app_common_smarthome);
        iconMap.put("com.nxt.svm", R.drawable.ico_laucher_list_app_common_svm);
        iconMap.put("com.fauto.car.weburlapplication", R.drawable.ico_laucher_list_app_common_fnw);
        iconMap.put("com.fauto.car.smartcar", R.drawable.ico_laucher_list_app_common_smartcar);
        iconMap.put("com.ivi.car.vehicle", R.drawable.ico_laucher_list_app_common_vehicle_setting);
        iconMap.put("com.hrap.app.security", R.drawable.ico_laucher_list_app_common_camera);
        iconMap.put("com.fpt.demo", R.drawable.ico_laucher_list_app_common_3d);
        iconMap.put("com.vncautomotive.cobaltlinksampleviewer.androidauto", R.drawable.ico_laucher_list_app_common_android_auto);
        iconMap.put("com.vncautomotive.cobaltlinksampleviewer.carplay", R.drawable.ico_laucher_list_app_common_carplay);
        APP_ICON_MAP = Collections.unmodifiableMap(iconMap);

        Map<String, String> nameMap = new HashMap<>();
        nameMap.put("com.fpt.demo", "3D Setup");
        APP_NAME_MAP = Collections.unmodifiableMap(nameMap);
    }
}
