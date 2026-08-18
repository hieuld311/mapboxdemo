package com.ivi.launcher.view.viewholder;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ivi.launcher.R;
import com.ivi.launcher.constant.HomeCardItem;
import com.ivi.launcher.constant.HomeCarouselConfig;
import com.ivi.launcher.model.home.NavMapSurfaceCoordinator;
import com.ivi.launcher.view.adapter.HomeCarouselAdapter;

public class HomeCardViewHolder extends RecyclerView.ViewHolder {
    private static final int COMPACT_APP_WIDTH_DP = 400;
    private static final int COMPACT_APP_HEIGHT_DP = 284;
    private static final int COMPACT_SHADOW_WIDTH_DP = 400;
    private static final int COMPACT_SHADOW_HEIGHT_DP = 116;
    private static final int FOCUS_APP_WIDTH_DP = 613;
    private static final int FOCUS_APP_HEIGHT_DP = 478;
    private static final int FOCUS_SHADOW_WIDTH_DP = 613;
    private static final int FOCUS_SHADOW_HEIGHT_DP = 127;
    private static final int MEDIA_PROGRESS_MAX = 1000;

    // Smooth swap from compact shadow proxy to real focus shadow near animation end.
    private static final float SHADOW_CROSS_FADE_START = 0.82f;

    private final HomeCarouselAdapter.CarouselHost host;
    private final View cardRoot;
    private final FrameLayout compactLayer;
    private final FrameLayout compactSlot;
    private final View compactShadowImage;
    private final FrameLayout focusLayer;
    private final FrameLayout focusSlot;
    private final View focusShadowImage;
    private final LayoutInflater inflater;
    // Process-wide singleton (see class doc) - never a per-ViewHolder controller, so any number
    // of TYPE_NAVIGATION ViewHolder instances the RecyclerView keeps around always share exactly
    // one live map-widget session. Does not touch HomeCarouselAdapter.java.
    private final NavMapSurfaceCoordinator mapSurfaceCoordinator;

    private ValueAnimator runningAnimator;
    private int currentType = -1;
    private int boundAdapterPosition = RecyclerView.NO_POSITION;
    // Cached nav compact/focus views (and crucially, the SurfaceView inside the focus one) - see
    // ensureCardLayout(). Reused whenever this ViewHolder switches back to TYPE_NAVIGATION
    // instead of inflating fresh ones, so HomeCarouselAdapter's lack of stable IDs (a
    // notifyDataSetChanged() pass, fired on every compact-card data push, can reassign this
    // ViewHolder to a different card type for one bind and back to nav on the very next) doesn't
    // hand attachNavMapSurface() a brand-new SurfaceView every time - HomeCardMapSurfaceController
    // can't recognize a new instance as already-attached, forcing a full
    // MapWidgetSurfaceService rebuild (a visible black flash) on essentially every compact update.
    private View cachedNavCompactView;
    private View cachedNavFocusView;

    public HomeCardViewHolder(
            @NonNull View itemView,
            @NonNull HomeCarouselAdapter.CarouselHost host
    ) {
        super(itemView);
        this.host = host;
        inflater = LayoutInflater.from(itemView.getContext());
        cardRoot = itemView.findViewById(R.id.cardRoot);
        compactLayer = itemView.findViewById(R.id.compactLayer);
        compactSlot = itemView.findViewById(R.id.compactSlot);
        compactShadowImage = itemView.findViewById(R.id.compactShadowImage);
        focusLayer = itemView.findViewById(R.id.focusLayer);
        focusSlot = itemView.findViewById(R.id.focusSlot);
        focusShadowImage = itemView.findViewById(R.id.focusShadowImage);
        mapSurfaceCoordinator = NavMapSurfaceCoordinator.getInstance(itemView.getContext());

        // Deliberately no release-on-detach here: the live map session is meant to stay warm
        // across ordinary RecyclerView churn (a swipe genuinely detaching this itemView, a data-
        // only rebind, or even a brief type switch-away-and-back - see cachedNavFocusView) so
        // swiping back to the nav card shows it instantly instead of rebuilding
        // MapWidgetSurfaceService's MapView from scratch. See applyFocusState()/
        // attachNavMapSurface() - the only remaining release path is IviLauncher.onDestroy()
        // (activity teardown).
    }

    public void bind(HomeCardItem item, int adapterPosition, boolean focused, boolean animate) {
        boundAdapterPosition = adapterPosition;
        ensureCardLayout(item.type);
        bindCardData(item);
        if (animate) {
            animateFocusState(focused);
        } else {
            applyFocusState(focused);
        }
    }

    private void ensureCardLayout(int type) {
        if (currentType == type && compactSlot.getChildCount() > 0 && focusSlot.getChildCount() > 0) {
            return;
        }
        currentType = type;
        compactSlot.removeAllViews();
        focusSlot.removeAllViews();

        View compactView;
        View focusView;
        if (type == HomeCardItem.TYPE_NAVIGATION && cachedNavFocusView != null) {
            // Reuse the cached nav views (see field doc) instead of inflating fresh ones -
            // keeps the same SurfaceView instance alive across a type switch-away-and-back.
            compactView = cachedNavCompactView;
            focusView = cachedNavFocusView;
        } else {
            compactView = inflater.inflate(getCompactLayoutRes(type), compactSlot, false);
            focusView = inflater.inflate(getFocusLayoutRes(type), focusSlot, false);
            if (type == HomeCardItem.TYPE_NAVIGATION) {
                cachedNavCompactView = compactView;
                cachedNavFocusView = focusView;
            }
        }
        compactSlot.addView(compactView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        focusSlot.addView(focusView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    private int getCompactLayoutRes(int type) {
        if (type == HomeCardItem.TYPE_PHONE) {
            return R.layout.card_phone_compact;
        }
        if (type == HomeCardItem.TYPE_NAVIGATION) {
            return R.layout.card_navigation_compact;
        }
        if (type == HomeCardItem.TYPE_CAR_INFO) {
            return R.layout.card_car_info_compact;
        }
        if (type == HomeCardItem.TYPE_VIDEO) {
            return R.layout.card_video_compact;
        }
        return R.layout.card_music_compact;
    }

    private int getFocusLayoutRes(int type) {
        if (type == HomeCardItem.TYPE_PHONE) {
            return R.layout.card_phone_focus;
        }
        if (type == HomeCardItem.TYPE_NAVIGATION) {
            return R.layout.card_navigation_focus;
        }
        if (type == HomeCardItem.TYPE_CAR_INFO) {
            return R.layout.card_car_info_focus;
        }
        if (type == HomeCardItem.TYPE_VIDEO) {
            return R.layout.card_video_focus;
        }
        return R.layout.card_music_focus;
    }

    private void bindCardData(@NonNull HomeCardItem item) {
        if (item.type == HomeCardItem.TYPE_NAVIGATION) {
            bindNaviCard(item);
            return;
        }
        if (item.type == HomeCardItem.TYPE_MUSIC) {
            bindMediaCard(
                    item,
                    R.id.musicCompactImage,
                    R.id.musicFocusImage,
                    R.id.musicCompactTitle,
                    R.id.musicFocusTitle,
                    R.id.musicCompactSubtitle,
                    R.id.musicFocusSubtitle,
                    R.id.musicCompactPlayButton,
                    R.id.musicFocusPlayButton,
                    R.id.musicFocusPrevButton,
                    R.id.musicFocusNextButton,
                    R.id.musicFocusProgress,
                    R.drawable.img_music_temp,
                    R.drawable.img_music_temp
            );
            return;
        }
        if (item.type == HomeCardItem.TYPE_VIDEO) {
            bindMediaCard(
                    item,
                    R.id.videoCompactImage,
                    R.id.videoFocusImage,
                    R.id.videoCompactTitle,
                    R.id.videoFocusTitle,
                    R.id.videoCompactSubtitle,
                    R.id.videoFocusSubtitle,
                    R.id.videoCompactPlayButton,
                    R.id.videoFocusPlayButton,
                    0,
                    0,
                    R.id.videoFocusProgress,
                    R.drawable.img_video_temp,
                    R.drawable.img_video_temp
            );
        }
    }

    private void bindMediaCard(
            @NonNull HomeCardItem item,
            int compactImageId,
            int focusImageId,
            int compactTitleId,
            int focusTitleId,
            int compactSubtitleId,
            int focusSubtitleId,
            int compactPlayButtonId,
            int focusPlayButtonId,
            int focusPrevButtonId,
            int focusNextButtonId,
            int focusProgressId,
            int compactPlaceholderRes,
            int focusPlaceholderRes
    ) {
        View compactView = compactSlot.getChildCount() > 0 ? compactSlot.getChildAt(0) : null;
        View focusView = focusSlot.getChildCount() > 0 ? focusSlot.getChildAt(0) : null;
        bindMediaCardView(
                compactView,
                item,
                compactImageId,
                compactTitleId,
                compactSubtitleId,
                compactPlayButtonId,
                0,
                0,
                0,
                compactPlaceholderRes
        );
        bindMediaCardView(
                focusView,
                item,
                focusImageId,
                focusTitleId,
                focusSubtitleId,
                focusPlayButtonId,
                focusPrevButtonId,
                focusNextButtonId,
                focusProgressId,
                focusPlaceholderRes
        );
    }

    private void bindMediaCardView(
            View root,
            @NonNull HomeCardItem item,
            int imageId,
            int titleId,
            int subtitleId,
            int playButtonId,
            int prevButtonId,
            int nextButtonId,
            int progressId,
            int placeholderRes
    ) {
        if (root == null) {
            return;
        }
        ImageView imageView = root.findViewById(imageId);
        TextView titleView = root.findViewById(titleId);
        TextView subtitleView = root.findViewById(subtitleId);
        ImageButton playButton = root.findViewById(playButtonId);
        ImageButton prevButton = prevButtonId == 0 ? null : root.findViewById(prevButtonId);
        ImageButton nextButton = nextButtonId == 0 ? null : root.findViewById(nextButtonId);
        ProgressBar progressBar = progressId == 0 ? null : root.findViewById(progressId);

        if (imageView != null) {
            imageView.setVisibility(View.VISIBLE);
            imageView.setAlpha(1f);
            imageView.clearAnimation();
            imageView.clearColorFilter();
            if (item.artworkBitmap != null) {
                imageView.setImageBitmap(item.artworkBitmap);
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else if (placeholderRes != 0) {
                imageView.setImageResource(placeholderRes);
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                imageView.setImageDrawable(null);
            }
        }

        host.logFocus("bindMediaImage"
                + " | type=" + item.type
                + ", imageId=" + imageId
                + ", rootId=" + root.getId()
                + ", hasArtwork=" + (item.artworkBitmap != null)
                + ", drawable=" + (imageView.getDrawable() != null)
                + ", visibility=" + imageView.getVisibility()
                + ", alpha=" + imageView.getAlpha()
                + ", width=" + imageView.getWidth()
                + ", height=" + imageView.getHeight()
                + ", placeholderRes=" + placeholderRes);

        if (titleView != null) {
            titleView.setText(isEmpty(item.primaryText) ? item.title : item.primaryText);
        }
        if (subtitleView != null) {
            subtitleView.setText(isEmpty(item.secondaryText) ? "" : item.secondaryText);
        }
        bindMediaProgress(progressBar, item);
        if (playButton != null) {
            playButton.setImageResource(item.playing
                    ? R.drawable.sel_ico_media_pause_l
                    : R.drawable.sel_ico_media_play_l);
            playButton.setBackgroundResource(R.drawable.bg_media_play_button);
            playButton.setEnabled(item.available);
        }
        if (prevButton != null) {
            prevButton.setImageResource(R.drawable.sel_ico_media_prev);
            prevButton.setBackground(null);
            prevButton.setForeground(null);
            prevButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            prevButton.setEnabled(item.available);
        }
        if (nextButton != null) {
            nextButton.setImageResource(R.drawable.sel_ico_media_next);
            nextButton.setBackground(null);
            nextButton.setForeground(null);
            nextButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            nextButton.setEnabled(item.available);
        }
        bindMediaButtonActions(item, playButton, prevButton, nextButton);
    }

    private void bindMediaProgress(ProgressBar progressBar, @NonNull HomeCardItem item) {
        if (progressBar == null) {
            return;
        }
        progressBar.setMax(MEDIA_PROGRESS_MAX);
        progressBar.setIndeterminate(false);
        progressBar.setEnabled(false);
        progressBar.setClickable(false);
        progressBar.setFocusable(false);
        progressBar.setFocusableInTouchMode(false);

        long durationMs = item.durationMs;
        long positionMs = item.positionMs;
        if (!item.available || durationMs <= 0L) {
            progressBar.setProgress(0);
            progressBar.setVisibility(View.INVISIBLE);
            return;
        }
        if (positionMs < 0L) {
            positionMs = 0L;
        }
        if (positionMs > durationMs) {
            positionMs = durationMs;
        }
        int progress = (int) ((positionMs * MEDIA_PROGRESS_MAX) / durationMs);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(progress);
    }

    private void bindMediaButtonActions(
            @NonNull HomeCardItem item,
            ImageButton playButton,
            ImageButton prevButton,
            ImageButton nextButton
    ) {
        if (playButton != null) {
            playButton.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                host.onMediaPlayPauseClicked(item, boundAdapterPosition);
            });
        }
        if (prevButton != null) {
            prevButton.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                host.onMediaPreviousClicked(item, boundAdapterPosition);
            });
        }
        if (nextButton != null) {
            nextButton.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                host.onMediaNextClicked(item, boundAdapterPosition);
            });
        }
    }

    private boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void bindNaviCard(@NonNull HomeCardItem item) {
        bindNaviCompactCard(item);
        bindNaviFocusCard(item);
    }

    private void bindNaviCompactCard(@NonNull HomeCardItem item) {
        View compactView = compactSlot.getChildCount() > 0 ? compactSlot.getChildAt(0) : null;
        if (compactView == null) return;

        View idleView = compactView.findViewById(R.id.navCompactIdleView);
        View tbtView = compactView.findViewById(R.id.navCompactTbtView);

        if (!item.naviActive) {
            if (idleView != null) idleView.setVisibility(View.VISIBLE);
            if (tbtView != null) tbtView.setVisibility(View.GONE);
            return;
        }

        if (idleView != null) idleView.setVisibility(View.GONE);
        if (tbtView != null) tbtView.setVisibility(View.VISIBLE);

        ImageView turnIcon = tbtView.findViewById(R.id.navCompactTurnIcon);
        TextView distanceView = tbtView.findViewById(R.id.navCompactDistance);
        TextView roadView = tbtView.findViewById(R.id.navCompactRoad);
        ProgressBar progressBar = tbtView.findViewById(R.id.navCompactProgressBar);
        TextView remainingView = tbtView.findViewById(R.id.navCompactRemainingDistance);
        TextView etaView = tbtView.findViewById(R.id.navCompactEta);

        if (turnIcon != null) {
            int iconRes = getTurnIconRes(item.naviTurnType);
            turnIcon.setImageResource(iconRes != 0 ? iconRes : R.drawable.ico_launcher_turn_by_turn_unknown_s);
        }
        if (distanceView != null) {
            String unit = formatDistanceUnit(item.naviStepUnit);
            distanceView.setText(item.naviStepDistance + " " + unit);
        }
        if (roadView != null) {
            roadView.setText(isEmpty(item.naviStepRoad) ? item.naviDestination : item.naviStepRoad);
        }
        if (progressBar != null) {
            progressBar.setMax(1000);
            progressBar.setIndeterminate(false);
            progressBar.setProgress(item.naviPercentTraveled);
        }
        if (remainingView != null) {
            if (!isEmpty(item.naviRemainingDistance)) {
                String unit = formatDistanceUnit(item.naviRemainingUnit);
                remainingView.setText(item.naviRemainingDistance + unit);
            } else {
                remainingView.setText("");
            }
        }
        if (etaView != null) {
            if (item.naviEtaMinutes > 0) {
                etaView.setText(item.naviEtaMinutes + "min");
            } else {
                etaView.setText("");
            }
        }
    }

    private void bindNaviFocusCard(@NonNull HomeCardItem item) {
        View focusView = focusSlot.getChildCount() > 0 ? focusSlot.getChildAt(0) : null;
        if (focusView == null) return;

        // Live embedded map (API 32+ only - see HomeCardMapSurfaceController/
        // NavMapSurfaceCoordinator) attach is driven entirely by focus transitions now (see
        // attachNavMapSurface(), called from applyFocusState()) - not from every bind() here, so
        // a routine data-only rebind while already focused never touches the map surface, and a
        // card only ever requests a surface once it has actually finished becoming the focused
        // card.

        View defaultView = focusView.findViewById(R.id.navFocusDefaultView);
        View tbtView = focusView.findViewById(R.id.navFocusTbtView);

        // On API 32+ the live widget already renders its own maneuver + trip-progress card
        // baked into the surface (see MapWidgetSurfaceService's progressCard), gated on the nav
        // app's own route-active state - showing the launcher's static text card on top would
        // duplicate it, so both static states are suppressed here. Below API 32 (no live
        // surface at all, see HomeCardMapSurfaceController#bind), fall back to the original
        // static default/TBT text views driven by item.naviActive.
        if (Build.VERSION.SDK_INT >= 32) {
            if (defaultView != null) defaultView.setVisibility(View.GONE);
            if (tbtView != null) tbtView.setVisibility(View.GONE);
            return;
        }

        if (!item.naviActive) {
            if (defaultView != null) defaultView.setVisibility(View.VISIBLE);
            if (tbtView != null) tbtView.setVisibility(View.GONE);
            return;
        }

        if (defaultView != null) defaultView.setVisibility(View.GONE);
        if (tbtView != null) tbtView.setVisibility(View.VISIBLE);

        ImageView turnIcon = tbtView.findViewById(R.id.navFocusTurnIcon);
        TextView directionView = tbtView.findViewById(R.id.navFocusDirection);
        TextView distanceView = tbtView.findViewById(R.id.navFocusDistance);
        TextView roadView = tbtView.findViewById(R.id.navFocusRoad);
        TextView destView = tbtView.findViewById(R.id.navFocusDestination);

        if (turnIcon != null) {
            int iconRes = getTurnIconRes(item.naviTurnType);
            if (iconRes != 0) {
                turnIcon.setImageResource(iconRes);
            } else {
                turnIcon.setImageDrawable(null);
            }
        }
        if (directionView != null) {
            directionView.setText(formatTurnType(item.naviTurnType));
        }
        if (distanceView != null) {
            String unit = formatDistanceUnit(item.naviStepUnit);
            distanceView.setText(item.naviStepDistance + " " + unit);
        }
        if (roadView != null) {
            roadView.setText(item.naviStepRoad);
        }
        if (destView != null) {
            if (isEmpty(item.naviDestination)) {
                destView.setVisibility(View.GONE);
            } else {
                destView.setVisibility(View.VISIBLE);
                destView.setText(item.naviDestination);
            }
        }
    }

    private String formatDistanceUnit(String unit) {
        if (unit == null) return "m";
        String lower = unit.toLowerCase();
        if (lower.contains("km") || lower.contains("kilometer")) return "km";
        return "m";
    }

    private int getTurnIconRes(String turnType) {
        if (turnType == null) return 0;
        switch (turnType) {
            case "TURN_NORMAL_RIGHT": return R.drawable.ico_launcher_turn_by_turn_turn_right_s;
            case "TURN_NORMAL_LEFT":  return R.drawable.ico_launcher_turn_by_turn_turn_left_s;
            case "TURN_SHARP_RIGHT":  return R.drawable.ico_launcher_turn_by_turn_turn_sharp_r_s;
            case "TURN_SHARP_LEFT":   return R.drawable.ico_launcher_turn_by_turn_turn_sharp_l_s;
            case "TURN_SLIGHT_RIGHT": return R.drawable.ico_launcher_turn_by_turn_turn_slight_r_s;
            case "TURN_SLIGHT_LEFT":  return R.drawable.ico_launcher_turn_by_turn_turn_slight_l_s;
            case "U_TURN_RIGHT":      return R.drawable.ico_launcher_turn_by_turn_turn_u_turn_r_s;
            case "U_TURN_LEFT":       return R.drawable.ico_launcher_turn_by_turn_turn_u_turn_l_s;
            case "U_TURN":            return R.drawable.ico_launcher_turn_by_turn_turn_u_turn_s;
            case "ROUNDABOUT_ENTER":  return R.drawable.ico_launcher_turn_by_turn_roundabout_s;
            case "DESTINATION":       return R.drawable.ico_launcher_turn_by_turn_turn_finish_s;
            default:                  return R.drawable.ico_launcher_turn_by_turn_unknown_s;
        }
    }

    private String formatTurnType(String turnType) {
        if (isEmpty(turnType)) return "";
        String spaced = turnType.replace("_", " ");
        String[] words = spaced.split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)));
                result.append(word.substring(1).toLowerCase());
                result.append(" ");
            }
        }
        return result.toString().trim();
    }

    private void applyFocusState(boolean focused) {
        if (runningAnimator != null) {
            runningAnimator.cancel();
            runningAnimator = null;
        }
        resizeRoot(focused ? HomeCarouselConfig.FOCUS_WIDTH_DP : HomeCarouselConfig.COMPACT_WIDTH_DP);
        setFocusLayerBottomMargin(focused ? 0 : HomeCarouselConfig.COMPACT_BOTTOM_MARGIN_DP);
        setCompactLayerBottomMargin(HomeCarouselConfig.COMPACT_BOTTOM_MARGIN_DP);
        resetTransforms();
        if (focused) {
            applyFocusSlotTransform(1f, 1f, 0f);
            focusLayer.setVisibility(View.VISIBLE);
            focusLayer.setAlpha(1f);
            focusShadowImage.setVisibility(View.VISIBLE);
            focusShadowImage.setAlpha(1f);
            compactLayer.setVisibility(View.INVISIBLE);
            compactLayer.setAlpha(0f);
            compactSlot.setAlpha(1f);
            compactShadowImage.setAlpha(1f);
        } else {
            applyFocusSlotTransform(getStartSlotScaleX(), getStartSlotScaleY(), getStartSlotTranslationY());
            focusLayer.setVisibility(View.INVISIBLE);
            focusLayer.setAlpha(0f);
            focusShadowImage.setVisibility(View.INVISIBLE);
            focusShadowImage.setAlpha(0f);
            compactLayer.setVisibility(View.VISIBLE);
            compactLayer.setAlpha(1f);
            compactSlot.setAlpha(1f);
            compactShadowImage.setVisibility(View.VISIBLE);
            compactShadowImage.setAlpha(1f);
        }
        // Every path that changes focus state - instant bind, a completed grow animation, or a
        // completed/skipped collapse - funnels through here once the state is actually settled,
        // so this is the single point where becoming focused (re)attaches the live map surface.
        // Deliberately no release counterpart when losing focus - see attachNavMapSurface() and
        // the constructor comment: the session stays warm across ordinary focus/scroll churn
        // instead of being torn down and rebuilt every time the user swipes away and back.
        if (currentType == HomeCardItem.TYPE_NAVIGATION && focused) {
            attachNavMapSurface();
        }
    }

    /**
     * Requests the live embedded map surface for this card's SurfaceView. Only called once this
     * card has fully become the focused nav card (see applyFocusState()), so the widget never
     * attaches to a SurfaceView whose on-screen bounds/transform are still mid-animation. A
     * no-op if this SurfaceView already holds the live surface (see
     * HomeCardMapSurfaceController#bind) - which is the common case once the session has been
     * left running from a prior focus, so re-focusing shows it instantly instead of rebuilding
     * MapWidgetSurfaceService's MapView from scratch.
     */
    private void attachNavMapSurface() {
        View focusView = focusSlot.getChildCount() > 0 ? focusSlot.getChildAt(0) : null;
        if (focusView == null) return;
        SurfaceView mapSurface = focusView.findViewById(R.id.navFocusMapSurface);
        if (mapSurface != null) {
            mapSurfaceCoordinator.bind(mapSurface);
        }
    }

    private void animateFocusState(boolean focused) {
        if (!focused) {
            animateUnfocusState();
            return;
        }
        if (runningAnimator != null) {
            runningAnimator.cancel();
        }
        final int targetAdapterPosition = boundAdapterPosition;
        host.beginFocusTransitionAnimator();
        final float startSlotScaleX = getStartSlotScaleX();
        final float startSlotScaleY = getStartSlotScaleY();
        final float startSlotTranslationY = getStartSlotTranslationY();
        final float endProxyShadowScaleX = getFocusToCompactShadowScaleX();
        final float endProxyShadowScaleY = getFocusToCompactShadowScaleY();
        final float endProxyShadowTranslationX = getProxyShadowEndTranslationX();
        resizeRoot(HomeCarouselConfig.COMPACT_WIDTH_DP);
        setFocusLayerBottomMargin(HomeCarouselConfig.COMPACT_BOTTOM_MARGIN_DP);
        setCompactLayerBottomMargin(HomeCarouselConfig.COMPACT_BOTTOM_MARGIN_DP);
        resetTransforms();
        applyFocusSlotTransform(startSlotScaleX, startSlotScaleY, startSlotTranslationY);
        compactLayer.setVisibility(View.VISIBLE);
        compactLayer.setAlpha(1f);
        compactSlot.setAlpha(0f);
        compactShadowImage.setVisibility(View.VISIBLE);
        compactShadowImage.setAlpha(1f);
        applyProxyCompactShadowTransform(1f, 1f, 0f);
        focusLayer.setVisibility(View.VISIBLE);
        focusLayer.setAlpha(1f);
        focusShadowImage.setVisibility(View.VISIBLE);
        focusShadowImage.setAlpha(0f);
        resetFocusShadowImageTransform();
        runningAnimator = ValueAnimator.ofFloat(0f, 1f);
        runningAnimator.setDuration(HomeCarouselConfig.FOCUS_TRANSITION_DURATION_MS);
        runningAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        final int[] lastRootWidth = {host.dpToPx(HomeCarouselConfig.COMPACT_WIDTH_DP)};
        runningAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            int compactWidthPx = host.dpToPx(HomeCarouselConfig.COMPACT_WIDTH_DP);
            int focusWidthPx = host.dpToPx(HomeCarouselConfig.FOCUS_WIDTH_DP);
            int currentRootWidth = (int) (compactWidthPx + (focusWidthPx - compactWidthPx) * progress);
            int currentBottomMargin = (int) (host.dpToPx(HomeCarouselConfig.COMPACT_BOTTOM_MARGIN_DP) * (1f - progress));
            ViewGroup.LayoutParams rootParams = cardRoot.getLayoutParams();
            rootParams.width = currentRootWidth;
            rootParams.height = host.dpToPx(HomeCarouselConfig.FOCUS_HEIGHT_DP);
            cardRoot.setLayoutParams(rootParams);
            int deltaWidth = currentRootWidth - lastRootWidth[0];
            if (deltaWidth != 0) {
                host.scrollCarouselBy(deltaWidth / 2);
                lastRootWidth[0] = currentRootWidth;
            }
            setFocusLayerBottomMarginPx(currentBottomMargin);
            setCompactLayerBottomMarginPx(currentBottomMargin);
            float slotScaleX = startSlotScaleX + (1f - startSlotScaleX) * progress;
            float slotScaleY = startSlotScaleY + (1f - startSlotScaleY) * progress;
            float slotTranslationY = startSlotTranslationY * (1f - progress);
            applyFocusSlotTransform(slotScaleX, slotScaleY, slotTranslationY);
            float proxyScaleX = 1f + (endProxyShadowScaleX - 1f) * progress;
            float proxyScaleY = 1f + (endProxyShadowScaleY - 1f) * progress;
            float proxyTranslationX = endProxyShadowTranslationX * progress;
            applyProxyCompactShadowTransform(proxyScaleX, proxyScaleY, proxyTranslationX);
            float swapProgress = getTailProgress(progress);
            compactShadowImage.setAlpha(1f - swapProgress);
            focusShadowImage.setAlpha(swapProgress);
        });
        runningAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean finished = false;
            @Override
            public void onAnimationEnd(Animator animation) {
                if (finished) return;
                finished = true;
                runningAnimator = null;
                applyFocusState(true);
                host.finishFocusTransitionAnimator();
                host.onFocusAnimationCompleted(targetAdapterPosition);
            }
            @Override
            public void onAnimationCancel(Animator animation) {
                if (finished) return;
                finished = true;
                runningAnimator = null;
                host.finishFocusTransitionAnimator();
            }
        });
        runningAnimator.start();
    }

    private void animateUnfocusState() {
        if (runningAnimator != null) {
            runningAnimator.cancel();
        }
        if (host.getCarouselScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            // A drag/fling is already repositioning this item via RecyclerView's own touch/scroll
            // handling. Unlike animateFocusState() (which locks touch via
            // host.beginFocusTransitionAnimator() while it runs), this collapse path is triggered
            // mid-drag by IviLauncher's SCROLL_STATE_DRAGGING listener and is never touch-locked -
            // animating cardRoot's width frame-by-frame below would race that concurrent
            // touch-driven scroll, which is what makes the shrinking card visually land on top of
            // a neighboring card during a swipe. Snap straight to the compact state instead; the
            // user's own gesture is already supplying the motion.
            runningAnimator = null;
            applyFocusState(false);
            return;
        }
        final float endSlotScaleX = getStartSlotScaleX();
        final float endSlotScaleY = getStartSlotScaleY();
        final float endSlotTranslationY = getStartSlotTranslationY();
        final float fullProxyShadowScaleX = getFocusToCompactShadowScaleX();
        final float fullProxyShadowScaleY = getFocusToCompactShadowScaleY();
        final float fullProxyShadowTranslationX = getProxyShadowEndTranslationX();
        resizeRoot(HomeCarouselConfig.FOCUS_WIDTH_DP);
        setFocusLayerBottomMargin(0);
        setCompactLayerBottomMargin(0);
        resetTransforms();
        applyFocusSlotTransform(1f, 1f, 0f);
        resetFocusShadowImageTransform();
        compactLayer.setVisibility(View.VISIBLE);
        compactLayer.setAlpha(1f);
        compactSlot.setAlpha(0f);
        compactShadowImage.setVisibility(View.VISIBLE);
        compactShadowImage.setAlpha(0f);
        applyProxyCompactShadowTransform(fullProxyShadowScaleX, fullProxyShadowScaleY, fullProxyShadowTranslationX);
        focusLayer.setVisibility(View.VISIBLE);
        focusLayer.setAlpha(1f);
        focusShadowImage.setVisibility(View.VISIBLE);
        focusShadowImage.setAlpha(1f);
        runningAnimator = ValueAnimator.ofFloat(0f, 1f);
        runningAnimator.setDuration(HomeCarouselConfig.FOCUS_COLLAPSE_ON_DRAG_DURATION_MS);
        runningAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        runningAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            int focusWidthPx = host.dpToPx(HomeCarouselConfig.FOCUS_WIDTH_DP);
            int compactWidthPx = host.dpToPx(HomeCarouselConfig.COMPACT_WIDTH_DP);
            int currentRootWidth = (int) (focusWidthPx + (compactWidthPx - focusWidthPx) * progress);
            int currentBottomMargin = (int) (host.dpToPx(HomeCarouselConfig.COMPACT_BOTTOM_MARGIN_DP) * progress);
            ViewGroup.LayoutParams rootParams = cardRoot.getLayoutParams();
            rootParams.width = currentRootWidth;
            rootParams.height = host.dpToPx(HomeCarouselConfig.FOCUS_HEIGHT_DP);
            cardRoot.setLayoutParams(rootParams);
            setFocusLayerBottomMarginPx(currentBottomMargin);
            setCompactLayerBottomMarginPx(currentBottomMargin);
            float slotScaleX = 1f + (endSlotScaleX - 1f) * progress;
            float slotScaleY = 1f + (endSlotScaleY - 1f) * progress;
            float slotTranslationY = endSlotTranslationY * progress;
            applyFocusSlotTransform(slotScaleX, slotScaleY, slotTranslationY);
            float proxyScaleX = fullProxyShadowScaleX + (1f - fullProxyShadowScaleX) * progress;
            float proxyScaleY = fullProxyShadowScaleY + (1f - fullProxyShadowScaleY) * progress;
            float proxyTranslationX = fullProxyShadowTranslationX * (1f - progress);
            applyProxyCompactShadowTransform(proxyScaleX, proxyScaleY, proxyTranslationX);
            float swapProgress = getTailProgress(progress);
            focusLayer.setAlpha(1f - progress);
            focusShadowImage.setAlpha(1f - swapProgress);
            compactShadowImage.setAlpha(swapProgress);
            compactSlot.setAlpha(progress);
        });
        runningAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean finished = false;
            @Override
            public void onAnimationEnd(Animator animation) {
                if (finished) return;
                finished = true;
                runningAnimator = null;
                applyFocusState(false);
                host.scheduleFocusFromCurrentCenterIfIdle("collapse-end");
            }
            @Override
            public void onAnimationCancel(Animator animation) {
                if (finished) return;
                finished = true;
                runningAnimator = null;
            }
        });
        runningAnimator.start();
    }

    private float getStartSlotScaleX() {
        return (float) getViewWidthPx(compactSlot, COMPACT_APP_WIDTH_DP)
                / getViewWidthPx(focusSlot, FOCUS_APP_WIDTH_DP);
    }

    private float getStartSlotScaleY() {
        return (float) getViewHeightPx(compactSlot, COMPACT_APP_HEIGHT_DP)
                / getViewHeightPx(focusSlot, FOCUS_APP_HEIGHT_DP);
    }

    private float getStartSlotTranslationY() {
        int focusLayerHeight = getViewHeightPx(focusLayer, HomeCarouselConfig.FOCUS_HEIGHT_DP);
        int compactLayerHeight = getViewHeightPx(compactLayer, HomeCarouselConfig.COMPACT_HEIGHT_DP);
        return focusLayerHeight - compactLayerHeight + compactSlot.getTop() - focusSlot.getTop();
    }

    private float getFocusToCompactShadowScaleX() {
        return (float) FOCUS_SHADOW_WIDTH_DP / COMPACT_SHADOW_WIDTH_DP;
    }

    private float getFocusToCompactShadowScaleY() {
        return (float) FOCUS_SHADOW_HEIGHT_DP / COMPACT_SHADOW_HEIGHT_DP;
    }

    private float getProxyShadowEndTranslationX() {
        float compactCenterX = getViewWidthPx(compactShadowImage, COMPACT_SHADOW_WIDTH_DP) / 2f;
        float focusCenterX = host.dpToPx(FOCUS_SHADOW_WIDTH_DP) / 2f;
        return focusCenterX - compactCenterX;
    }

    private float getTailProgress(float progress) {
        if (progress <= SHADOW_CROSS_FADE_START) {
            return 0f;
        }
        return Math.min(1f, (progress - SHADOW_CROSS_FADE_START) / (1f - SHADOW_CROSS_FADE_START));
    }

    private int getViewWidthPx(View view, int fallbackDp) {
        int width = view.getWidth();
        return width > 0 ? width : host.dpToPx(fallbackDp);
    }

    private int getViewHeightPx(View view, int fallbackDp) {
        int height = view.getHeight();
        return height > 0 ? height : host.dpToPx(fallbackDp);
    }

    private void applyFocusSlotTransform(float slotScaleX, float slotScaleY, float slotTranslationY) {
        focusLayer.setScaleX(1f);
        focusLayer.setScaleY(1f);
        focusLayer.setTranslationX(0f);
        focusLayer.setTranslationY(0f);
        focusSlot.setPivotX(0f);
        focusSlot.setPivotY(0f);
        focusSlot.setScaleX(slotScaleX);
        focusSlot.setScaleY(slotScaleY);
        focusSlot.setTranslationX(0f);
        focusSlot.setTranslationY(slotTranslationY);
    }

    private void applyProxyCompactShadowTransform(float shadowScaleX, float shadowScaleY, float shadowTranslationX) {
        compactShadowImage.setPivotX(host.dpToPx(COMPACT_SHADOW_WIDTH_DP) / 2f);
        compactShadowImage.setPivotY(host.dpToPx(COMPACT_SHADOW_HEIGHT_DP));
        compactShadowImage.setScaleX(shadowScaleX);
        compactShadowImage.setScaleY(shadowScaleY);
        compactShadowImage.setTranslationX(shadowTranslationX);
        compactShadowImage.setTranslationY(0f);
    }

    private void resetFocusShadowImageTransform() {
        focusShadowImage.setPivotX(host.dpToPx(FOCUS_SHADOW_WIDTH_DP) / 2f);
        focusShadowImage.setPivotY(host.dpToPx(FOCUS_SHADOW_HEIGHT_DP));
        focusShadowImage.setScaleX(1f);
        focusShadowImage.setScaleY(1f);
        focusShadowImage.setTranslationX(0f);
        focusShadowImage.setTranslationY(0f);
    }

    private void resizeRoot(int widthDp) {
        ViewGroup.LayoutParams rootParams = cardRoot.getLayoutParams();
        if (rootParams == null) {
            rootParams = new RecyclerView.LayoutParams(
                    host.dpToPx(widthDp),
                    host.dpToPx(HomeCarouselConfig.FOCUS_HEIGHT_DP)
            );
        }
        rootParams.width = host.dpToPx(widthDp);
        rootParams.height = host.dpToPx(HomeCarouselConfig.FOCUS_HEIGHT_DP);
        cardRoot.setLayoutParams(rootParams);
    }

    private void setFocusLayerBottomMargin(int bottomMarginDp) {
        setFocusLayerBottomMarginPx(host.dpToPx(bottomMarginDp));
    }

    private void setFocusLayerBottomMarginPx(int bottomMarginPx) {
        ViewGroup.MarginLayoutParams focusParams =
                (ViewGroup.MarginLayoutParams) focusLayer.getLayoutParams();
        focusParams.width = host.dpToPx(HomeCarouselConfig.FOCUS_WIDTH_DP);
        focusParams.height = host.dpToPx(HomeCarouselConfig.FOCUS_HEIGHT_DP);
        focusParams.bottomMargin = bottomMarginPx;
        focusLayer.setLayoutParams(focusParams);
    }

    private void setCompactLayerBottomMargin(int bottomMarginDp) {
        setCompactLayerBottomMarginPx(host.dpToPx(bottomMarginDp));
    }

    private void setCompactLayerBottomMarginPx(int bottomMarginPx) {
        ViewGroup.MarginLayoutParams compactParams =
                (ViewGroup.MarginLayoutParams) compactLayer.getLayoutParams();
        compactParams.width = host.dpToPx(HomeCarouselConfig.COMPACT_WIDTH_DP);
        compactParams.height = host.dpToPx(HomeCarouselConfig.COMPACT_HEIGHT_DP);
        compactParams.bottomMargin = bottomMarginPx;
        compactLayer.setLayoutParams(compactParams);
    }

    private void resetTransforms() {
        cardRoot.setTranslationX(0f);
        cardRoot.setTranslationY(0f);
        cardRoot.setScaleX(1f);
        cardRoot.setScaleY(1f);
        compactLayer.setTranslationX(0f);
        compactLayer.setTranslationY(0f);
        compactLayer.setScaleX(1f);
        compactLayer.setScaleY(1f);
        compactSlot.setTranslationX(0f);
        compactSlot.setTranslationY(0f);
        compactSlot.setScaleX(1f);
        compactSlot.setScaleY(1f);
        compactSlot.setAlpha(1f);
        compactShadowImage.setTranslationX(0f);
        compactShadowImage.setTranslationY(0f);
        compactShadowImage.setScaleX(1f);
        compactShadowImage.setScaleY(1f);
        compactShadowImage.setAlpha(1f);
        focusLayer.setTranslationX(0f);
        focusLayer.setTranslationY(0f);
        focusLayer.setScaleX(1f);
        focusLayer.setScaleY(1f);
        focusSlot.setTranslationX(0f);
        focusSlot.setTranslationY(0f);
        focusSlot.setScaleX(1f);
        focusSlot.setScaleY(1f);
        focusShadowImage.setTranslationX(0f);
        focusShadowImage.setTranslationY(0f);
        focusShadowImage.setScaleX(1f);
        focusShadowImage.setScaleY(1f);
        focusShadowImage.setAlpha(1f);
    }
}
