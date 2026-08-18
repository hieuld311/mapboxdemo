package com.ivi.launcher.model.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class HomeMediaSessionProvider {
    private static final String TAG = "HomeMediaSessionProvider";
    private static final long MEDIA_PROGRESS_TICK_MS = 1000L;

    public interface Listener {
        void onMusicMediaChanged(@NonNull MediaInfo info);
        void onVideoMediaChanged(@NonNull MediaInfo info);
    }

    public static final class MediaInfo {
        public final String packageName;
        public final String title;
        public final String subtitle;
        public final Bitmap artwork;
        public final boolean playing;
        public final long positionMs;
        public final long durationMs;
        public final long actions;

        private MediaInfo(
                @NonNull String packageName,
                @NonNull String title,
                @NonNull String subtitle,
                @Nullable Bitmap artwork,
                boolean playing,
                long positionMs,
                long durationMs,
                long actions
        ) {
            this.packageName = packageName;
            this.title = title;
            this.subtitle = subtitle;
            this.artwork = artwork;
            this.playing = playing;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.actions = actions;
        }

        public static MediaInfo empty(@NonNull String packageName) {
            return new MediaInfo(packageName, "", "", null, false, 0L, 0L, 0L);
        }
    }

    private final Context mContext;
    private final Handler mHandler;
    private final String mMusicPackage;
    private final String mVideoPackage;
    private final Listener mListener;
    private final MediaSessionManager mMediaSessionManager;

    @Nullable
    private MediaController mMusicController;
    @Nullable
    private MediaController mVideoController;

    private final Runnable mProgressTickRunnable = new Runnable() {
        @Override
        public void run() {
            dispatchMusic();
            dispatchVideo();
            scheduleProgressTickIfNeeded();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener mActiveSessionsChangedListener =
            controllers -> {
                Log.d(TAG, "onActiveSessionsChanged count="
                        + (controllers == null ? 0 : controllers.size()));
                refreshControllers(controllers);
            };

    private final MediaController.Callback mMusicCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            dispatchMusic();
            scheduleProgressTickIfNeeded();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            dispatchMusic();
            scheduleProgressTickIfNeeded();
        }

        @Override
        public void onSessionDestroyed() {
            Log.d(TAG, "Music session destroyed");
            clearMusicController();
            mListener.onMusicMediaChanged(MediaInfo.empty(mMusicPackage));
            scheduleProgressTickIfNeeded();
        }
    };

    private final MediaController.Callback mVideoCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            dispatchVideo();
            scheduleProgressTickIfNeeded();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            dispatchVideo();
            scheduleProgressTickIfNeeded();
        }

        @Override
        public void onSessionDestroyed() {
            Log.d(TAG, "Video session destroyed");
            clearVideoController();
            mListener.onVideoMediaChanged(MediaInfo.empty(mVideoPackage));
            scheduleProgressTickIfNeeded();
        }
    };

    public HomeMediaSessionProvider(
            @NonNull Context context,
            @NonNull Handler handler,
            @NonNull String musicPackage,
            @NonNull String videoPackage,
            @NonNull Listener listener
    ) {
        mContext = context.getApplicationContext();
        mHandler = handler;
        mMusicPackage = musicPackage;
        mVideoPackage = videoPackage;
        mListener = listener;
        mMediaSessionManager =
                (MediaSessionManager) mContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    public void start() {
        if (mMediaSessionManager == null) {
            Log.w(TAG, "start ignored because MediaSessionManager is null");
            mListener.onMusicMediaChanged(MediaInfo.empty(mMusicPackage));
            mListener.onVideoMediaChanged(MediaInfo.empty(mVideoPackage));
            return;
        }
        try {
            mMediaSessionManager.addOnActiveSessionsChangedListener(
                    mActiveSessionsChangedListener,
                    null,
                    mHandler
            );
        } catch (SecurityException e) {
            Log.e(TAG, "addOnActiveSessionsChangedListener failed", e);
        }
        try {
            refreshControllers(mMediaSessionManager.getActiveSessions(null));
        } catch (SecurityException e) {
            Log.e(TAG, "getActiveSessions failed", e);
            mListener.onMusicMediaChanged(MediaInfo.empty(mMusicPackage));
            mListener.onVideoMediaChanged(MediaInfo.empty(mVideoPackage));
            scheduleProgressTickIfNeeded();
        }
    }

    public void stop() {
        mHandler.removeCallbacks(mProgressTickRunnable);
        if (mMediaSessionManager != null) {
            try {
                mMediaSessionManager.removeOnActiveSessionsChangedListener(
                        mActiveSessionsChangedListener);
            } catch (Throwable throwable) {
                Log.w(TAG, "removeOnActiveSessionsChangedListener failed", throwable);
            }
        }
        clearMusicController();
        clearVideoController();
    }

    public void toggleMusicPlayPause() {
        Log.d(TAG, "toggleMusicPlayPause | controller=" + mMusicController);
        dispatchMusicMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "music-play-pause");
    }

    public void toggleVideoPlayPause() {
        Log.d(TAG, "toggleVideoPlayPause | controller=" + mVideoController);
        togglePlayPause(mVideoController, "video");
    }

    public void skipMusicToNext() {
        Log.d(TAG, "skipMusicToNext | controller=" + mMusicController);
        dispatchMusicMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, "music-next");
    }

    public void skipMusicToPrevious() {
        Log.d(TAG, "skipMusicToPrevious | controller=" + mMusicController);
        dispatchMusicMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "music-previous");
    }

    public void skipVideoToNext() {
        Log.d(TAG, "skipVideoToNext ignored because Video card has no next button");
    }

    public void skipVideoToPrevious() {
        Log.d(TAG, "skipVideoToPrevious ignored because Video card has no previous button");
    }

    private void refreshControllers(@Nullable List<MediaController> controllers) {
        List<MediaController> safeControllers =
                controllers == null ? new ArrayList<>() : controllers;
        MediaController newMusicController = null;
        MediaController newVideoController = null;
        for (MediaController controller : safeControllers) {
            if (controller == null) {
                continue;
            }
            String packageName = controller.getPackageName();
            Log.d(TAG, "active media controller package=" + packageName
                    + ", score=" + getControllerScore(controller)
                    + ", title=" + getControllerTitle(controller)
                    + ", state=" + getControllerState(controller)
                    + ", actions=" + getControllerActions(controller));
            if (mMusicPackage.equals(packageName)) {
                newMusicController = chooseBetterController(newMusicController, controller);
            } else if (mVideoPackage.equals(packageName)) {
                newVideoController = chooseBetterController(newVideoController, controller);
            }
        }
        updateMusicController(newMusicController);
        updateVideoController(newVideoController);
        dumpController("selected music", mMusicController);
        dumpController("selected video", mVideoController);
        dispatchMusic();
        dispatchVideo();
        scheduleProgressTickIfNeeded();
    }

    private MediaController chooseBetterController(
            @Nullable MediaController current,
            @NonNull MediaController candidate
    ) {
        if (current == null) {
            return candidate;
        }
        int currentScore = getControllerScore(current);
        int candidateScore = getControllerScore(candidate);
        if (candidateScore >= currentScore) {
            return candidate;
        }
        return current;
    }

    private int getControllerScore(@NonNull MediaController controller) {
        int score = 0;
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        if (metadata != null) {
            score += 100;
            String title = getMetadataString(metadata, MediaMetadata.METADATA_KEY_TITLE);
            if (!TextUtils.isEmpty(title)) {
                score += 50;
            }
        }
        if (state != null) {
            long actions = state.getActions();
            int stateValue = state.getState();
            if (actions != 0L) {
                score += 20;
            }
            if (stateValue == PlaybackState.STATE_PLAYING) {
                score += 80;
            } else if (stateValue == PlaybackState.STATE_PAUSED) {
                score += 70;
            } else if (stateValue == PlaybackState.STATE_BUFFERING
                    || stateValue == PlaybackState.STATE_CONNECTING) {
                score += 60;
            } else if (stateValue == PlaybackState.STATE_ERROR) {
                score += 30;
            } else if (stateValue == PlaybackState.STATE_STOPPED) {
                score += 20;
            }
        }
        return score;
    }

    private int getControllerState(@NonNull MediaController controller) {
        PlaybackState state = controller.getPlaybackState();
        return state == null ? PlaybackState.STATE_NONE : state.getState();
    }

    private long getControllerActions(@NonNull MediaController controller) {
        PlaybackState state = controller.getPlaybackState();
        return state == null ? 0L : state.getActions();
    }

    private String getControllerTitle(@NonNull MediaController controller) {
        MediaMetadata metadata = controller.getMetadata();
        if (metadata == null) {
            return "";
        }
        return getMetadataString(metadata, MediaMetadata.METADATA_KEY_TITLE);
    }

    private void updateMusicController(@Nullable MediaController controller) {
        if (mMusicController == controller) {
            return;
        }
        clearMusicController();
        mMusicController = controller;
        if (mMusicController != null) {
            mMusicController.registerCallback(mMusicCallback, mHandler);
            Log.d(TAG, "updateMusicController selected | package="
                    + mMusicController.getPackageName()
                    + ", title=" + getControllerTitle(mMusicController)
                    + ", state=" + getControllerState(mMusicController)
                    + ", actions=" + getControllerActions(mMusicController));
        }
    }

    private void updateVideoController(@Nullable MediaController controller) {
        if (mVideoController == controller) {
            return;
        }
        clearVideoController();
        mVideoController = controller;
        if (mVideoController != null) {
            mVideoController.registerCallback(mVideoCallback, mHandler);
            Log.d(TAG, "updateVideoController selected | package="
                    + mVideoController.getPackageName()
                    + ", title=" + getControllerTitle(mVideoController)
                    + ", state=" + getControllerState(mVideoController)
                    + ", actions=" + getControllerActions(mVideoController));
        }
    }

    private void clearMusicController() {
        if (mMusicController != null) {
            try {
                mMusicController.unregisterCallback(mMusicCallback);
            } catch (Throwable throwable) {
                Log.w(TAG, "unregister music callback failed", throwable);
            }
            mMusicController = null;
        }
        scheduleProgressTickIfNeeded();
    }

    private void clearVideoController() {
        if (mVideoController != null) {
            try {
                mVideoController.unregisterCallback(mVideoCallback);
            } catch (Throwable throwable) {
                Log.w(TAG, "unregister video callback failed", throwable);
            }
            mVideoController = null;
        }
        scheduleProgressTickIfNeeded();
    }

    private void dispatchMusic() {
        if (mMusicController == null) {
            mListener.onMusicMediaChanged(MediaInfo.empty(mMusicPackage));
            return;
        }
        mListener.onMusicMediaChanged(buildMediaInfo(mMusicController, mMusicPackage));
    }

    private void dispatchVideo() {
        if (mVideoController == null) {
            mListener.onVideoMediaChanged(MediaInfo.empty(mVideoPackage));
            return;
        }
        mListener.onVideoMediaChanged(buildMediaInfo(mVideoController, mVideoPackage));
    }

    private MediaInfo buildMediaInfo(
            @NonNull MediaController controller,
            @NonNull String fallbackPackageName
    ) {
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState playbackState = controller.getPlaybackState();
        String title = "";
        String subtitle = "";
        Bitmap artwork = null;
        long durationMs = 0L;
        if (metadata != null) {
            title = getMetadataString(metadata, MediaMetadata.METADATA_KEY_TITLE);
            subtitle = getMetadataString(metadata, MediaMetadata.METADATA_KEY_ARTIST);
            if (TextUtils.isEmpty(subtitle)) {
                subtitle = getMetadataString(metadata, MediaMetadata.METADATA_KEY_ALBUM);
            }
            artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (artwork == null) {
                artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            }
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }
        boolean playing = false;
        long positionMs = 0L;
        long actions = 0L;
        if (playbackState != null) {
            int state = playbackState.getState();
            playing = state == PlaybackState.STATE_PLAYING
                    || state == PlaybackState.STATE_BUFFERING
                    || state == PlaybackState.STATE_CONNECTING;
            positionMs = getCurrentPositionMs(playbackState, durationMs, playing);
            actions = playbackState.getActions();
        }
        String packageName = controller.getPackageName();
        if (packageName == null) {
            packageName = fallbackPackageName;
        }
        return new MediaInfo(
                packageName,
                safe(title),
                safe(subtitle),
                artwork,
                playing,
                positionMs,
                durationMs,
                actions
        );
    }

    private long getCurrentPositionMs(
            @NonNull PlaybackState playbackState,
            long durationMs,
            boolean playing
    ) {
        long positionMs = Math.max(0L, playbackState.getPosition());
        long lastUpdateTimeMs = playbackState.getLastPositionUpdateTime();
        float speed = playbackState.getPlaybackSpeed();
        if (playing && lastUpdateTimeMs > 0L && speed > 0f) {
            long deltaMs = Math.max(0L, SystemClock.elapsedRealtime() - lastUpdateTimeMs);
            positionMs += (long) (deltaMs * speed);
        }
        if (durationMs > 0L && positionMs > durationMs) {
            positionMs = durationMs;
        }
        return positionMs;
    }

    private void scheduleProgressTickIfNeeded() {
        mHandler.removeCallbacks(mProgressTickRunnable);
        if (isControllerPlaying(mMusicController) || isControllerPlaying(mVideoController)) {
            mHandler.postDelayed(mProgressTickRunnable, MEDIA_PROGRESS_TICK_MS);
        }
    }

    private boolean isControllerPlaying(@Nullable MediaController controller) {
        if (controller == null) {
            return false;
        }
        PlaybackState state = controller.getPlaybackState();
        if (state == null) {
            return false;
        }
        int stateValue = state.getState();
        return stateValue == PlaybackState.STATE_PLAYING
                || stateValue == PlaybackState.STATE_BUFFERING
                || stateValue == PlaybackState.STATE_CONNECTING;
    }

    private void togglePlayPause(@Nullable MediaController controller, @NonNull String label) {
        if (controller == null) {
            Log.w(TAG, "togglePlayPause ignored | " + label + " controller is null");
            return;
        }
        PlaybackState state = controller.getPlaybackState();
        long actions = state == null ? 0L : state.getActions();
        int stateValue = state == null ? PlaybackState.STATE_NONE : state.getState();
        boolean playing = stateValue == PlaybackState.STATE_PLAYING
                || stateValue == PlaybackState.STATE_BUFFERING
                || stateValue == PlaybackState.STATE_CONNECTING;
        Log.d(TAG, "togglePlayPause | label=" + label
                + ", package=" + controller.getPackageName()
                + ", state=" + stateValue
                + ", actions=" + actions
                + ", playing=" + playing
                + ", title=" + getControllerTitle(controller));
        if (playing) {
            if ((actions & PlaybackState.ACTION_PAUSE) != 0) {
                controller.getTransportControls().pause();
            } else {
                dispatchMediaKeyToController(controller, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, label);
            }
        } else {
            if ((actions & PlaybackState.ACTION_PLAY) != 0) {
                controller.getTransportControls().play();
            } else {
                dispatchMediaKeyToController(controller, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, label);
            }
        }
    }

    private void dispatchMusicMediaKey(int keyCode, @NonNull String reason) {
        if (mMusicController == null) {
            Log.w(TAG, "dispatchMusicMediaKey ignored | controller is null | reason=" + reason
                    + ", keyCode=" + keyCode);
            return;
        }
        PlaybackState state = mMusicController.getPlaybackState();
        int stateValue = state == null ? PlaybackState.STATE_NONE : state.getState();
        long actions = state == null ? 0L : state.getActions();
        Log.d(TAG, "dispatchMusicMediaKey start | reason=" + reason
                + ", package=" + mMusicController.getPackageName()
                + ", state=" + stateValue
                + ", actions=" + actions
                + ", title=" + getControllerTitle(mMusicController)
                + ", keyCode=" + keyCode);
        boolean handledByController =
                dispatchMediaKeyToController(mMusicController, keyCode, reason);
        if (!handledByController) {
            Log.w(TAG, "dispatchMusicMediaKey controller did not handle key, fallback AudioManager"
                    + " | reason=" + reason
                    + ", keyCode=" + keyCode);
            dispatchMediaKeyToAudioManager(keyCode, reason);
        }
        dispatchMusic();
        scheduleProgressTickIfNeeded();
    }

    private boolean dispatchMediaKeyToController(
            @NonNull MediaController controller,
            int keyCode,
            @NonNull String reason
    ) {
        long now = System.currentTimeMillis();
        KeyEvent downEvent = new KeyEvent(
                now,
                now,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0
        );
        KeyEvent upEvent = new KeyEvent(
                now,
                now,
                KeyEvent.ACTION_UP,
                keyCode,
                0
        );
        boolean downHandled = controller.dispatchMediaButtonEvent(downEvent);
        boolean upHandled = controller.dispatchMediaButtonEvent(upEvent);
        Log.d(TAG, "dispatchMediaKeyToController result | reason=" + reason
                + ", package=" + controller.getPackageName()
                + ", keyCode=" + keyCode
                + ", downHandled=" + downHandled
                + ", upHandled=" + upHandled);
        return downHandled || upHandled;
    }

    private void dispatchMediaKeyToAudioManager(int keyCode, @NonNull String reason) {
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            Log.w(TAG, "dispatchMediaKeyToAudioManager ignored | AudioManager is null"
                    + " | reason=" + reason
                    + ", keyCode=" + keyCode);
            return;
        }
        long now = System.currentTimeMillis();
        KeyEvent downEvent = new KeyEvent(
                now,
                now,
                KeyEvent.ACTION_DOWN,
                keyCode,
                0
        );
        KeyEvent upEvent = new KeyEvent(
                now,
                now,
                KeyEvent.ACTION_UP,
                keyCode,
                0
        );
        audioManager.dispatchMediaKeyEvent(downEvent);
        audioManager.dispatchMediaKeyEvent(upEvent);
        Log.d(TAG, "dispatchMediaKeyToAudioManager sent | reason=" + reason
                + ", keyCode=" + keyCode);
    }

    private void dumpController(@NonNull String label, @Nullable MediaController controller) {
        if (controller == null) {
            Log.w(TAG, "dumpController | " + label + " controller is null");
            return;
        }
        PlaybackState state = controller.getPlaybackState();
        MediaMetadata metadata = controller.getMetadata();
        int stateValue = state == null ? PlaybackState.STATE_NONE : state.getState();
        long actions = state == null ? 0L : state.getActions();
        Log.d(TAG, "dumpController | " + label
                + ", package=" + controller.getPackageName()
                + ", state=" + stateValue
                + ", actions=" + actions
                + ", title=" + getMetadataStringSafe(metadata, MediaMetadata.METADATA_KEY_TITLE)
                + ", artist=" + getMetadataStringSafe(metadata, MediaMetadata.METADATA_KEY_ARTIST));
    }

    private String getMetadataString(@NonNull MediaMetadata metadata, @NonNull String key) {
        CharSequence text = metadata.getText(key);
        return text == null ? "" : text.toString();
    }

    private String getMetadataStringSafe(@Nullable MediaMetadata metadata, @NonNull String key) {
        if (metadata == null) {
            return "";
        }
        return getMetadataString(metadata, key);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
