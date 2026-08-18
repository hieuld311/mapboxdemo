package com.ivi.launcher.constant;

import android.graphics.Bitmap;

public final class HomeCardItem {
    public static final int TYPE_PHONE = 1;
    public static final int TYPE_NAVIGATION = 2;
    public static final int TYPE_CAR_INFO = 3;
    public static final int TYPE_MUSIC = 4;
    public static final int TYPE_VIDEO = 5;

    public final int type;

    public String packageName = "";
    public String activityName = "";

    public String title = "";
    public String primaryText = "";
    public String secondaryText = "";

    public boolean available = true;
    public boolean restricted = false;
    public String restrictionReason = "";

    public boolean playing = false;
    public long positionMs = 0L;
    public long durationMs = 0L;
    public long mediaActions = 0L;

    public Bitmap artworkBitmap;
    public long updatedAtMillis = 0L;

    // Navigation TBT fields (TYPE_NAVIGATION only)
    public boolean naviActive = false;
    public String naviTurnType = "";
    public String naviStepDistance = "";
    public String naviStepUnit = "";
    public String naviStepRoad = "";
    public String naviDestination = "";
    // Route-level fields (for compact card bottom row)
    public String naviRemainingDistance = "";
    public String naviRemainingUnit = "";
    public int naviEtaMinutes = 0;
    // Overall route fraction traveled, scaled 0-1000 (see
    // HomeNaviDataProvider#percentTraveledToProgress) - drives the compact card's live progress
    // bar (see HomeCardViewHolder#bindNaviCompactCard).
    public int naviPercentTraveled = 0;

    public HomeCardItem(int type) {
        this.type = type;
    }

    public HomeCardItem copy() {
        HomeCardItem copy = new HomeCardItem(type);
        copy.packageName = packageName;
        copy.activityName = activityName;
        copy.title = title;
        copy.primaryText = primaryText;
        copy.secondaryText = secondaryText;
        copy.available = available;
        copy.restricted = restricted;
        copy.restrictionReason = restrictionReason;
        copy.playing = playing;
        copy.positionMs = positionMs;
        copy.durationMs = durationMs;
        copy.mediaActions = mediaActions;
        copy.artworkBitmap = artworkBitmap;
        copy.updatedAtMillis = updatedAtMillis;
        copy.naviActive = naviActive;
        copy.naviTurnType = naviTurnType;
        copy.naviStepDistance = naviStepDistance;
        copy.naviStepUnit = naviStepUnit;
        copy.naviStepRoad = naviStepRoad;
        copy.naviDestination = naviDestination;
        copy.naviRemainingDistance = naviRemainingDistance;
        copy.naviRemainingUnit = naviRemainingUnit;
        copy.naviEtaMinutes = naviEtaMinutes;
        copy.naviPercentTraveled = naviPercentTraveled;
        return copy;
    }
}