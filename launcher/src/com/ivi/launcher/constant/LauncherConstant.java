/*
 * Copyright (C) 2020, FPT Software, All Right Reserved.
 */

package com.ivi.launcher.constant;

public class LauncherConstant {
    public static final int PRIMARY_AREA = 1;
    public static final int SECONDARY_AREA = 2;
    public static final int WIDGET_AREA2 = 1;
    public static final int WIDGET_AREA3 = 2;
    public static final int APP_WIDGET_COUNT = 2;
    public static final int HOST_ID = 1234;
    public static final String AREA = "area";
    public static final String PENDING_APPWIDGET_ID  = "pending_appwidget";

    public static final String PKG_MAPS = "com.ivi.car.navigation";
    public static final String CLS_MAPS = "com.ivi.car.navigation.ui.MainActivity";

    public static final String PKG_DIALER = "com.android.car.dialer";
    public static final String CLS_DIALER = "com.android.car.dialer.ui.TelecomActivity";

    public static final String PKG_FAKE_HERE = "com.fakeheremap";
    public static final String PKG_AUTH_HERE = "com.here.hnod.client";
    public static final String PKG_MEDIA = "com.ivi.media";
    public static final String CLS_MEDIA = "com.ivi.media.ui.MainActivity";

    public static final String WIFI_NAME_DEFAULT = "IVI BOOTH";
    public static final String WIFI_PASS_DEFAULT = "12345678";

    public static final String WIFI_AP_NAME_DEFAULT = "FPT_IVI_Hotspot";
    public static final String WIFI_AP_PASS_DEFAULT = "12345678";

    public static final long DELAY_START_SECONDARY_LAUNCHER = 1000L;
    public static final String SECONDARY_LAUNCHER_PACKAGE = "com.ivi.launcher";
    public static final String SECONDARY_LAUNCHER_CLASS = "com.ivi.launcher.viewsecond.IviSecondLauncher";
    public static final String CMD_SECONDARY_LAUNCHER = "am start -n "+SECONDARY_LAUNCHER_PACKAGE+"/"+SECONDARY_LAUNCHER_CLASS+" --display 2";

    public static final String SECOND_LAUNCHER_PACKAGE = "com.fauto.app.fautosecondlauncher";
    public static final String SECOND_LAUNCHER_CLASS = "com.fauto.app.fautosecondlauncher.AllAppsMainActivity";
    public static final String CMD_SECONDARY_DISPLAY = "am start -n "+SECOND_LAUNCHER_PACKAGE+"/"+SECOND_LAUNCHER_CLASS+" --display 2";

    public static final long DELAY_START_HUD = 1000L;
    public static final String HVAC_PACKAGE = "com.fauto.climate";
    public static final String HVAC_CLASS = "ccom.epicgames.unreal.GameActivity";
    public static final String CMD_START_HVAC = "am start -n "+HVAC_PACKAGE+"/"+HVAC_CLASS+" --display 3";
}
