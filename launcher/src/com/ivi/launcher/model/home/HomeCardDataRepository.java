package com.ivi.launcher.model.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ivi.launcher.constant.HomeCardItem;

import java.util.ArrayList;

public class HomeCardDataRepository implements HomeMediaSessionProvider.Listener,
        HomeNaviDataProvider.Listener {
    private static final String TAG = "HomeCardDataRepository";

    private final Context mContext;
    private final Handler mMainHandler;
    private final String mMusicPackage;
    private final String mVideoPackage;
    private final ArrayList<HomeCardItem> mCards = new ArrayList<>();

    private final HomeDisplayPolicyProvider mDisplayPolicyProvider;
    private final HomeMediaSessionProvider mMediaSessionProvider;
    private final HomeNaviDataProvider mNaviDataProvider;

    @Nullable
    private HomeCardDataListener mListener;

    public HomeCardDataRepository(
            @NonNull Context context,
            @NonNull String musicPackage,
            @NonNull String videoPackage
    ) {
        mContext = context.getApplicationContext();
        mMainHandler = new Handler(Looper.getMainLooper());
        mMusicPackage = musicPackage;
        mVideoPackage = videoPackage;

        mDisplayPolicyProvider = new HomeDisplayPolicyProvider(mContext);
        mMediaSessionProvider = new HomeMediaSessionProvider(
                mContext,
                mMainHandler,
                mMusicPackage,
                mVideoPackage,
                this
        );
        mNaviDataProvider = new HomeNaviDataProvider(mContext, mMainHandler, this);

        mCards.addAll(createDefaultCards(mMusicPackage, mVideoPackage));
        applyVideoPolicy();
    }

    public static ArrayList<HomeCardItem> createDefaultCards(
            @NonNull String musicPackage,
            @NonNull String videoPackage
    ) {
        ArrayList<HomeCardItem> cards = new ArrayList<>();

        HomeCardItem phone = new HomeCardItem(HomeCardItem.TYPE_PHONE);
        phone.title = "Phone";
        phone.primaryText = "Phone";
        cards.add(phone);

        HomeCardItem navigation = new HomeCardItem(HomeCardItem.TYPE_NAVIGATION);
        navigation.title = "Navigation";
        navigation.primaryText = "Navigation";
        cards.add(navigation);

        HomeCardItem carInfo = new HomeCardItem(HomeCardItem.TYPE_CAR_INFO);
        carInfo.title = "Car Info";
        carInfo.primaryText = "Car Info";
        carInfo.secondaryText = "";
        cards.add(carInfo);

        HomeCardItem music = new HomeCardItem(HomeCardItem.TYPE_MUSIC);
        music.title = "Music";
        music.primaryText = "No media playing";
        music.secondaryText = "Tap to open Music";
        music.packageName = musicPackage;
        cards.add(music);

        HomeCardItem video = new HomeCardItem(HomeCardItem.TYPE_VIDEO);
        video.title = "Video";
        video.primaryText = "No video playing";
        video.secondaryText = "Tap to open Video";
        video.packageName = videoPackage;
        cards.add(video);

        return cards;
    }

    public void start() {
        Log.d(TAG, "start");
        notifyChanged();
        mMediaSessionProvider.start();
        mNaviDataProvider.start();
    }

    public void stop() {
        Log.d(TAG, "stop");
        mMediaSessionProvider.stop();
        mNaviDataProvider.stop();
        mListener = null;
    }

    public void registerListener(@NonNull HomeCardDataListener listener) {
        mListener = listener;
        notifyChanged();
    }

    public void unregisterListener(@NonNull HomeCardDataListener listener) {
        if (mListener == listener) {
            mListener = null;
        }
    }

    public void performPlayPause(int cardType) {
        if (cardType == HomeCardItem.TYPE_MUSIC) {
            mMediaSessionProvider.toggleMusicPlayPause();
        } else if (cardType == HomeCardItem.TYPE_VIDEO) {
            mMediaSessionProvider.toggleVideoPlayPause();
        }
    }

    public void performPrevious(int cardType) {
        if (cardType == HomeCardItem.TYPE_MUSIC) {
            mMediaSessionProvider.skipMusicToPrevious();
        }
    }

    public void performNext(int cardType) {
        if (cardType == HomeCardItem.TYPE_MUSIC) {
            mMediaSessionProvider.skipMusicToNext();
        }
    }

    @Override
    public void onMusicMediaChanged(@NonNull HomeMediaSessionProvider.MediaInfo info) {
        mMainHandler.post(() -> {
            HomeCardItem item = findCard(HomeCardItem.TYPE_MUSIC);
            if (item == null) {
                return;
            }

            applyMediaInfoToCard(item, info, "Music", "No media playing", "Tap to open Music");
            notifyChanged();
        });
    }

    @Override
    public void onVideoMediaChanged(@NonNull HomeMediaSessionProvider.MediaInfo info) {
        mMainHandler.post(() -> {
            HomeCardItem item = findCard(HomeCardItem.TYPE_VIDEO);
            if (item == null) {
                return;
            }

            applyMediaInfoToCard(item, info, "Video", "No video playing", "Tap to open Video");
            applyVideoPolicy();
            notifyChanged();
        });
    }

    private void applyMediaInfoToCard(
            @NonNull HomeCardItem item,
            @NonNull HomeMediaSessionProvider.MediaInfo info,
            @NonNull String title,
            @NonNull String fallbackPrimary,
            @NonNull String fallbackSecondary
    ) {
        item.title = title;
        item.packageName = info.packageName;
        item.primaryText = isEmpty(info.title) ? fallbackPrimary : info.title;
        item.secondaryText = isEmpty(info.subtitle)
                ? (info.playing ? "Playing" : fallbackSecondary)
                : info.subtitle;
        item.artworkBitmap = info.artwork;
        item.playing = info.playing;
        item.positionMs = info.positionMs;
        item.durationMs = info.durationMs;
        item.mediaActions = info.actions;
        item.available = !isEmpty(info.title) || info.playing;
        item.updatedAtMillis = System.currentTimeMillis();
    }

    private void applyVideoPolicy() {
        boolean allowed = mDisplayPolicyProvider.isVideoWidgetAllowed();
        HomeCardItem video = findCard(HomeCardItem.TYPE_VIDEO);

        if (allowed) {
            if (video == null) {
                HomeCardItem newVideo = new HomeCardItem(HomeCardItem.TYPE_VIDEO);
                newVideo.title = "Video";
                newVideo.primaryText = "No video playing";
                newVideo.secondaryText = "Tap to open Video";
                newVideo.packageName = mVideoPackage;
                mCards.add(newVideo);
            }
        } else if (video != null) {
            mCards.remove(video);
        }
    }

    @Override
    public void onNaviDataChanged(@NonNull HomeNaviDataProvider.NaviInfo info) {
        HomeCardItem item = findCard(HomeCardItem.TYPE_NAVIGATION);
        if (item == null) return;

        if (info.isSnapshotUpdate) {
            // A snapshot tick carries no TBT text data - only touch the map image so it
            // can't blank out whatever the TBT channel last set on this item.
            item.naviMapSnapshot = info.mapSnapshot;
            Log.d(TAG, "onNaviDataChanged: map snapshot applied | hasBitmap="
                    + (info.mapSnapshot != null));
            notifyChanged();
            return;
        }

        item.naviActive = info.active;
        item.naviTurnType = info.turnType;
        item.naviStepDistance = info.stepDistance;
        item.naviStepUnit = info.stepUnit;
        item.naviStepRoad = info.stepRoad;
        item.naviDestination = info.destination;
        item.naviRemainingDistance = info.remainingDistance;
        item.naviRemainingUnit = info.remainingUnit;
        item.naviEtaMinutes = info.etaMinutes;
        notifyChanged();
    }

    @Nullable
    private HomeCardItem findCard(int type) {
        for (HomeCardItem item : mCards) {
            if (item != null && item.type == type) {
                return item;
            }
        }
        return null;
    }

    private void notifyChanged() {
        if (mListener == null) {
            return;
        }

        ArrayList<HomeCardItem> snapshot = new ArrayList<>();
        for (HomeCardItem item : mCards) {
            if (item != null) {
                snapshot.add(item.copy());
            }
        }

        mListener.onHomeCardsChanged(snapshot);
    }

    private boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}