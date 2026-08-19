package com.ivi.launcher.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.penfeizhou.animation.apng.APNGDrawable;
import com.ivi.launcher.R;
import com.ivi.launcher.constant.HomeCardItem;
import com.ivi.launcher.view.adapter.HomeCarouselAdapter;
import com.ivi.launcher.model.home.HomeCardDataRepository;

import java.util.ArrayList;

public class IviLauncher extends AppCompatActivity implements HomeCarouselAdapter.CarouselHost {
    private static final String TAG = "HomeCarouselFocus";

    private static final int SLIDE_WIDTH_DP = 1437;
    private static final int ITEM_GAP_DP = 12;
    private static final int FOCUS_WIDTH_DP = 613;
    private static final int LOOP_ITEM_COUNT = Integer.MAX_VALUE;
    private static final int INITIAL_REAL_FOCUS_INDEX = 1;

    private static final long FOCUS_ANIMATION_START_DELAY_MS = 500L;
    private static final long FOCUS_IDLE_FALLBACK_DELAY_MS = 80L;
    private static final long CENTER_SCROLL_DURATION_MS = 700L;

    private static final float FLING_DISTANCE_FACTOR = 0.12f;
    private static final int MAX_FLING_SCROLL_DP = 640;
    private static final int CENTER_SCROLL_SKIP_THRESHOLD_DP = 4;

    private static final String PHONE_PACKAGE = "com.ivi.phoneapp";
    private static final String PHONE_ACTIVITY = "com.ivi.phoneapp.MainActivity";
    private static final String NAVIGATION_PACKAGE = "com.ivi.car.navigation";
    private static final String NAVIGATION_ACTIVITY = "com.ivi.car.navigation.ui.MainActivity";
    private static final String CAR_INFO_PACKAGE = "com.ivi.vehicleinfo";
    private static final String CAR_INFO_ACTIVITY = "com.ivi.vehicleinfo.VehicleInfoMainActivity";

    private static final String MUSIC_PACKAGE = "com.ivi.media.cid";
    private static final String MUSIC_ACTIVITY = "com.ivi.media.MainActivity";

    private static final String VIDEO_PACKAGE = "com.ivi.video.cid";
    private static final String VIDEO_ACTIVITY = "com.ivi.cid.ui.VideoLibraryActivity";

    private ImageView homeBG;
    private RecyclerView homeCarousel;
    private View carScrim;

    private LinearLayoutManager carouselLayoutManager;
    private HomeCarouselAdapter carouselAdapter;

    private final ArrayList<HomeCardItem> cardItems = new ArrayList<>();

    private boolean isFocusTransitionLocked = false;
    private int runningFocusAnimatorCount = 0;

    private final Handler focusAnimationHandler = new Handler(Looper.getMainLooper());

    @Nullable
    private Runnable pendingFocusAnimationRunnable;

    @Nullable
    private Runnable pendingCenterScrollRunnable;

    private int pendingFocusPosition = RecyclerView.NO_POSITION;

    @Nullable
    private ValueAnimator centerScrollAnimator;
    @Nullable
    private HomeCardDataRepository homeCardDataRepository;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        forceNormalOpaqueLauncherWindow();
        setContentView(R.layout.ivi_launcher);
        forceTransparentSystemBars();

        homeBG = findViewById(R.id.homeBG);
        homeCarousel = findViewById(R.id.homeCarousel);
        carScrim = findViewById(R.id.carScrim);

        setupCarScrim();
        setupCards();
        setupCarousel();
        setupHomeCardDataRepository();
        setApngDrawable(homeBG, "cob_home_12fps.png", false);
    }

    private void forceNormalOpaqueLauncherWindow() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);
    }

    private void forceTransparentSystemBars() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        int systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;

        getWindow().getDecorView().setSystemUiVisibility(systemUiVisibility);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
    }

    private void setApngDrawable(ImageView iv, String url, boolean freeze) {
        if (iv == null) {
            logFocusWarn("setApngDrawable skipped because target ImageView is null, url=" + url);
            return;
        }

        iv.setBackgroundColor(Color.BLACK);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);

        try {
            APNGDrawable apngDrawable = APNGDrawable.fromAsset(getApplicationContext(), url);
            if (freeze) {
                apngDrawable.setLoopLimit(1);
            }

            iv.setImageDrawable(apngDrawable);
            logFocus("setApngDrawable success url=" + url + ", freeze=" + freeze);
        } catch (Throwable throwable) {
            Log.e(TAG, "setApngDrawable failed url=" + url, throwable);
            iv.setImageDrawable(null);
            iv.setBackgroundColor(Color.BLACK);
        }
    }

    private void setupCarScrim() {
        if (carScrim == null) {
            return;
        }

        GradientDrawable scrim = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{
                        Color.parseColor("#00050816"),
                        Color.parseColor("#33050816"),
                        Color.parseColor("#99050816")
                }
        );

        carScrim.setBackground(scrim);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            carScrim.setElevation(dpToPx(12));
        }
    }

    private void setupCards() {
        cardItems.clear();
        cardItems.addAll(HomeCardDataRepository.createDefaultCards(MUSIC_PACKAGE, VIDEO_PACKAGE));
    }

    private void setupHomeCardDataRepository() {
        homeCardDataRepository = new HomeCardDataRepository(
                this,
                MUSIC_PACKAGE,
                VIDEO_PACKAGE
        );

        homeCardDataRepository.registerListener(cards -> {
            logFocus("HomeCardDataRepository update | size=" + cards.size());

            cardItems.clear();
            for (HomeCardItem item : cards) {
                if (item != null) {
                    cardItems.add(item.copy());
                }
            }

            logFocus("HomeCardDataRepository synced | cardItemsSize=" + cardItems.size());

            if (carouselAdapter != null) {
                carouselAdapter.setItems(cards);
                logFocus("HomeCardDataRepository adapter updated | adapterItemCount="
                        + carouselAdapter.getItemCount());
                scheduleFocusFromCurrentCenterIfIdle("home-card-data-update");
            }
        });

        homeCardDataRepository.start();
    }

    private void setupCarousel() {
        carouselLayoutManager = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        homeCarousel.setLayoutManager(carouselLayoutManager);

        homeCarousel.setPadding(0, 0, 0, 0);
        homeCarousel.setClipToPadding(true);
        homeCarousel.setClipChildren(true);
        homeCarousel.setOverScrollMode(View.OVER_SCROLL_NEVER);
        homeCarousel.setHasFixedSize(false);
        homeCarousel.setItemAnimator(null);

        homeCarousel.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(
                    @NonNull RecyclerView recyclerView,
                    @NonNull MotionEvent event
            ) {
                int action = event.getActionMasked();

                logFocus("onInterceptTouchEvent | action=" + action
                        + ", locked=" + isFocusTransitionLocked()
                        + ", scrollState=" + scrollStateToString(recyclerView.getScrollState())
                        + ", pending=" + adapterPositionInfo(pendingFocusPosition));

                if (!isFocusTransitionLocked() && action == MotionEvent.ACTION_DOWN) {
                    cancelPendingFocusAnimation();
                    cancelCenterScrollAnimation();
                }

                boolean intercept = isFocusTransitionLocked();

                if (intercept) {
                    logFocusWarn("Touch intercepted because focus transition is locked");
                }

                return intercept;
            }
        });

        homeCarousel.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                cancelCenterScrollAnimation();
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                logFocus("onTouch end | action=" + action
                        + ", scrollState=" + scrollStateToString(homeCarousel.getScrollState())
                        + ", locked=" + isFocusTransitionLocked()
                        + ", pending=" + adapterPositionInfo(pendingFocusPosition)
                        + ", adapterFocus=" + (carouselAdapter == null
                        ? "adapter-null" : carouselAdapter.getFocusedPositionInfo()));

                scheduleFocusFromCurrentCenterIfIdle("touch-end");
            }

            return false;
        });

        homeCarousel.setOnFlingListener(new RecyclerView.OnFlingListener() {
            @Override
            public boolean onFling(int velocityX, int velocityY) {
                logFocus("onFling | velocityX=" + velocityX + ", velocityY=" + velocityY
                        + ", locked=" + isFocusTransitionLocked()
                        + ", scrollState=" + scrollStateToString(homeCarousel.getScrollState()));

                if (isFocusTransitionLocked()) {
                    logFocusWarn("onFling consumed because focus transition is locked");
                    return true;
                }

                cancelPendingFocusAnimation();
                cancelCenterScrollAnimation();

                int maxFlingScroll = dpToPx(MAX_FLING_SCROLL_DP);
                int dx = (int) (velocityX * FLING_DISTANCE_FACTOR);

                if (dx > maxFlingScroll) {
                    dx = maxFlingScroll;
                } else if (dx < -maxFlingScroll) {
                    dx = -maxFlingScroll;
                }

                logFocus("onFling | final dx=" + dx + ", maxFlingScroll=" + maxFlingScroll);

                if (dx != 0) {
                    homeCarousel.smoothScrollBy(dx, 0);
                } else {
                    logFocusWarn("onFling produced dx=0");
                }

                return true;
            }
        });

        homeCarousel.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(
                    @NonNull Rect outRect,
                    @NonNull View view,
                    @NonNull RecyclerView parent,
                    @NonNull RecyclerView.State state
            ) {
                outRect.right = dpToPx(ITEM_GAP_DP);
            }
        });

        carouselAdapter = new HomeCarouselAdapter(cardItems, this);
        homeCarousel.setAdapter(carouselAdapter);

        int startPosition = getLoopStartPosition(INITIAL_REAL_FOCUS_INDEX);
        homeCarousel.post(() -> {
            if (carouselAdapter == null || carouselLayoutManager == null || homeCarousel == null) {
                logFocusWarn("initial focus skipped | carousel not ready");
                return;
            }

            carouselAdapter.setFocusedPosition(startPosition, false);
            scrollFocusedItemToCenterForInitialLayout(startPosition);

            homeCarousel.post(() -> {
                if (carouselAdapter == null || homeCarousel == null) {
                    logFocusWarn("initial focus rebind skipped | carousel not ready");
                    return;
                }

                logFocus("initial focus force rebind after first layout | position="
                        + adapterPositionInfo(startPosition));
                carouselAdapter.setFocusedPosition(startPosition, false);
            });
        });

        homeCarousel.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                logFocus("onScrollStateChanged | newState=" + scrollStateToString(newState)
                        + ", locked=" + isFocusTransitionLocked()
                        + ", runningAnimators=" + runningFocusAnimatorCount
                        + ", pending=" + adapterPositionInfo(pendingFocusPosition)
                        + ", childCount=" + recyclerView.getChildCount()
                        + ", adapterFocus=" + (carouselAdapter == null
                        ? "adapter-null" : carouselAdapter.getFocusedPositionInfo()));

                if (isFocusTransitionLocked()) {
                    logFocusWarn("onScrollStateChanged ignored because focus transition is locked | state="
                            + scrollStateToString(newState));
                    return;
                }

                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    logFocus("DRAGGING | cancel pending focus and collapse current focus");
                    cancelPendingFocusAnimation();
                    cancelCenterScrollAnimation();
                    carouselAdapter.clearFocusOnUserDrag(true);
                    return;
                }

                if (newState == RecyclerView.SCROLL_STATE_SETTLING) {
                    logFocus("SETTLING | cancel pending focus");
                    cancelPendingFocusAnimation();
                    cancelCenterScrollAnimation();
                    return;
                }

                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    scheduleFocusFromCurrentCenterIfIdle("scroll-idle");
                }
            }
        });
    }

    @Override
    public void onHomeCardClicked(@NonNull HomeCardItem item, int adapterPosition) {
        logFocus("onHomeCardClicked | type=" + item.type
                + ", position=" + adapterPositionInfo(adapterPosition)
                + ", locked=" + isFocusTransitionLocked()
                + ", scrollState=" + scrollStateToString(getCarouselScrollState()));

        if (isFocusTransitionLocked()) {
            logFocusWarn("onHomeCardClicked ignored because focus transition is locked");
            return;
        }

        if (adapterPosition == RecyclerView.NO_POSITION) {
            logFocusWarn("onHomeCardClicked ignored because adapter position is NO_POSITION");
            return;
        }

        Intent intent = buildLaunchIntent(item);
        if (intent == null) {
            logFocusWarn("onHomeCardClicked failed because launch intent is null, type=" + item.type);
            return;
        }

        launchHomeCardIntent(intent, item.type);
    }

    private Intent buildLaunchIntent(@NonNull HomeCardItem item) {
        if (item.type == HomeCardItem.TYPE_PHONE) {
            return createExplicitLaunchIntent(PHONE_PACKAGE, PHONE_ACTIVITY);
        }

        if (item.type == HomeCardItem.TYPE_NAVIGATION) {
            return createExplicitLaunchIntent(NAVIGATION_PACKAGE, NAVIGATION_ACTIVITY);
        }

        if (item.type == HomeCardItem.TYPE_CAR_INFO) {
            return createExplicitLaunchIntent(CAR_INFO_PACKAGE, CAR_INFO_ACTIVITY);
        }

        if (item.type == HomeCardItem.TYPE_MUSIC) {
            return createExplicitLaunchIntent(MUSIC_PACKAGE, MUSIC_ACTIVITY);
        }

        if (item.type == HomeCardItem.TYPE_VIDEO) {
            return createExplicitLaunchIntent(VIDEO_PACKAGE, VIDEO_ACTIVITY);
        }

        return null;
    }

    private Intent createPackageLaunchIntent(@NonNull String packageName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            logFocusWarn("createPackageLaunchIntent failed | package=" + packageName);
            return null;
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return intent;
    }

    private Intent createExplicitLaunchIntent(@NonNull String packageName, @NonNull String className) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(packageName, className));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return intent;
    }

    private void launchHomeCardIntent(@NonNull Intent intent, int itemType) {
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            int displayId = getCurrentDisplayId();
            options.setLaunchDisplayId(displayId);

            logFocus("launchHomeCardIntent | type=" + itemType
                    + ", intent=" + intent
                    + ", displayId=" + displayId);

            startActivity(intent, options.toBundle());
        } catch (Throwable throwable) {
            Log.e(TAG, "launchHomeCardIntent failed | type=" + itemType
                    + ", intent=" + intent, throwable);
        }
    }

    private int getCurrentDisplayId() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getDisplay() != null) {
            return getDisplay().getDisplayId();
        }

        return getWindowManager().getDefaultDisplay().getDisplayId();
    }

    @Override
    public void onFocusAnimationCompleted(int adapterPosition) {
        logFocus("onFocusAnimationCompleted | position=" + adapterPositionInfo(adapterPosition)
                + ", locked=" + isFocusTransitionLocked()
                + ", scrollState=" + scrollStateToString(getCarouselScrollState()));

        if (adapterPosition == RecyclerView.NO_POSITION) {
            return;
        }

        scheduleCenterScrollAfterDelay(adapterPosition, "focus-animation-completed");
    }

    private void scheduleCenterScrollAfterDelay(int adapterPosition, @NonNull String reason) {
        cancelPendingCenterScroll();

        pendingCenterScrollRunnable = () -> {
            int targetPosition = adapterPosition;

            logFocus("pendingCenterScrollRunnable fired | position="
                    + adapterPositionInfo(targetPosition)
                    + ", reason=" + reason
                    + ", locked=" + isFocusTransitionLocked()
                    + ", scrollState=" + scrollStateToString(getCarouselScrollState()));

            pendingCenterScrollRunnable = null;

            if (targetPosition == RecyclerView.NO_POSITION) {
                logFocusWarn("pendingCenterScrollRunnable abort | target NO_POSITION");
                return;
            }

            if (isFocusTransitionLocked()) {
                logFocusWarn("pendingCenterScrollRunnable abort | focus transition locked");
                return;
            }

            if (homeCarousel == null
                    || homeCarousel.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
                logFocusWarn("pendingCenterScrollRunnable abort | recycler null or not IDLE");
                return;
            }

            smoothCenterAdapterPosition(targetPosition, reason);
        };

        logFocus("scheduleCenterScrollAfterDelay posted | position="
                + adapterPositionInfo(adapterPosition)
                + ", delayMs=" + FOCUS_ANIMATION_START_DELAY_MS
                + ", reason=" + reason);

        focusAnimationHandler.postDelayed(
                pendingCenterScrollRunnable,
                FOCUS_ANIMATION_START_DELAY_MS
        );
    }

    private void cancelPendingCenterScroll() {
        if (pendingCenterScrollRunnable != null) {
            logFocus("cancelPendingCenterScroll");
            focusAnimationHandler.removeCallbacks(pendingCenterScrollRunnable);
            pendingCenterScrollRunnable = null;
        }
    }

    private void smoothCenterAdapterPosition(int adapterPosition, @NonNull String reason) {
        if (homeCarousel == null || carouselLayoutManager == null) {
            logFocusWarn("smoothCenterAdapterPosition abort | recycler/layout null | reason=" + reason);
            return;
        }

        if (isFocusTransitionLocked()) {
            logFocusWarn("smoothCenterAdapterPosition abort | locked | reason=" + reason);
            return;
        }

        if (homeCarousel.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            logFocusWarn("smoothCenterAdapterPosition abort | not IDLE | reason=" + reason
                    + ", state=" + scrollStateToString(homeCarousel.getScrollState()));
            return;
        }

        View child = carouselLayoutManager.findViewByPosition(adapterPosition);
        if (child == null) {
            logFocusWarn("smoothCenterAdapterPosition abort | child null | position="
                    + adapterPositionInfo(adapterPosition) + ", reason=" + reason);
            return;
        }

        int recyclerCenterX = homeCarousel.getWidth() / 2;
        int childCenterX = (child.getLeft() + child.getRight()) / 2;
        int dx = childCenterX - recyclerCenterX;
        int skipThreshold = dpToPx(CENTER_SCROLL_SKIP_THRESHOLD_DP);

        logFocus("smoothCenterAdapterPosition | position=" + adapterPositionInfo(adapterPosition)
                + ", reason=" + reason
                + ", childLeft=" + child.getLeft()
                + ", childRight=" + child.getRight()
                + ", childCenterX=" + childCenterX
                + ", recyclerCenterX=" + recyclerCenterX
                + ", dx=" + dx
                + ", skipThreshold=" + skipThreshold);

        if (Math.abs(dx) <= skipThreshold) {
            logFocus("smoothCenterAdapterPosition skipped because item is already near center");
            return;
        }

        animateRecyclerScrollBy(dx, reason);
    }

    private void animateRecyclerScrollBy(int dx, @NonNull String reason) {
        cancelCenterScrollAnimation();

        if (homeCarousel == null || dx == 0) {
            return;
        }

        final int[] lastValue = {0};

        centerScrollAnimator = ValueAnimator.ofInt(0, dx);
        centerScrollAnimator.setDuration(CENTER_SCROLL_DURATION_MS);
        centerScrollAnimator.setInterpolator(new DecelerateInterpolator());

        centerScrollAnimator.addUpdateListener(animation -> {
            if (homeCarousel == null) {
                return;
            }

            int value = (int) animation.getAnimatedValue();
            int delta = value - lastValue[0];
            lastValue[0] = value;

            if (delta != 0) {
                homeCarousel.scrollBy(delta, 0);
            }
        });

        centerScrollAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                logFocus("centerScrollAnimator end | dx=" + dx + ", reason=" + reason);
                centerScrollAnimator = null;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                logFocus("centerScrollAnimator cancel | dx=" + dx + ", reason=" + reason);
                centerScrollAnimator = null;
            }
        });

        logFocus("animateRecyclerScrollBy start | dx=" + dx + ", reason=" + reason);
        centerScrollAnimator.start();
    }

    private void cancelCenterScrollAnimation() {
        cancelPendingCenterScroll();

        if (centerScrollAnimator != null) {
            logFocus("cancelCenterScrollAnimation");
            centerScrollAnimator.cancel();
            centerScrollAnimator = null;
        }
    }

    @Override
    public void scheduleFocusFromCurrentCenterIfIdle(@NonNull String reason) {
        focusAnimationHandler.postDelayed(() -> {
            logFocus("scheduleFocusFromCurrentCenterIfIdle fired | reason=" + reason
                    + ", locked=" + isFocusTransitionLocked()
                    + ", scrollState=" + scrollStateToString(homeCarousel == null
                    ? -1 : homeCarousel.getScrollState())
                    + ", pending=" + adapterPositionInfo(pendingFocusPosition)
                    + ", adapterFocus=" + (carouselAdapter == null
                    ? "adapter-null" : carouselAdapter.getFocusedPositionInfo()));

            if (homeCarousel == null || carouselAdapter == null) {
                logFocusWarn("scheduleFocusFromCurrentCenterIfIdle abort | recycler/adpater null | reason=" + reason);
                return;
            }

            if (isFocusTransitionLocked()) {
                logFocusWarn("scheduleFocusFromCurrentCenterIfIdle abort | locked | reason=" + reason);
                return;
            }

            if (homeCarousel.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
                logFocusWarn("scheduleFocusFromCurrentCenterIfIdle abort | not IDLE | reason=" + reason
                        + ", scrollState=" + scrollStateToString(homeCarousel.getScrollState()));
                return;
            }

            int nearestPosition = findNearestPositionToCenter();

            logFocus("scheduleFocusFromCurrentCenterIfIdle nearest | reason=" + reason
                    + ", nearest=" + adapterPositionInfo(nearestPosition));

            if (nearestPosition != RecyclerView.NO_POSITION) {
                scheduleFocusAnimationAfterDelay(nearestPosition);
            } else {
                logFocusWarn("scheduleFocusFromCurrentCenterIfIdle abort | nearest NO_POSITION | reason=" + reason);
            }
        }, FOCUS_IDLE_FALLBACK_DELAY_MS);
    }

    private void scheduleFocusAnimationAfterDelay(int adapterPosition) {
        logFocus("scheduleFocusAnimationAfterDelay request | target=" + adapterPositionInfo(adapterPosition)
                + ", delayMs=" + FOCUS_ANIMATION_START_DELAY_MS
                + ", locked=" + isFocusTransitionLocked()
                + ", scrollState=" + scrollStateToString(homeCarousel == null
                ? -1 : homeCarousel.getScrollState())
                + ", existingPending=" + adapterPositionInfo(pendingFocusPosition));

        if (adapterPosition == RecyclerView.NO_POSITION) {
            logFocusWarn("scheduleFocusAnimationAfterDelay ignored because target is NO_POSITION");
            return;
        }

        cancelPendingFocusAnimation();
        cancelCenterScrollAnimation();

        pendingFocusPosition = adapterPosition;
        pendingFocusAnimationRunnable = () -> {
            int targetPosition = pendingFocusPosition;

            logFocus("pendingFocusAnimationRunnable fired | target=" + adapterPositionInfo(targetPosition)
                    + ", locked=" + isFocusTransitionLocked()
                    + ", scrollState=" + scrollStateToString(homeCarousel == null
                    ? -1 : homeCarousel.getScrollState())
                    + ", childCount=" + (homeCarousel == null ? -1 : homeCarousel.getChildCount())
                    + ", adapterFocus=" + (carouselAdapter == null
                    ? "adapter-null" : carouselAdapter.getFocusedPositionInfo()));

            pendingFocusAnimationRunnable = null;
            pendingFocusPosition = RecyclerView.NO_POSITION;

            if (targetPosition == RecyclerView.NO_POSITION) {
                logFocusWarn("pendingFocusAnimationRunnable abort | target is NO_POSITION");
                return;
            }

            if (isFocusTransitionLocked()) {
                logFocusWarn("pendingFocusAnimationRunnable abort | focus transition is locked");
                return;
            }

            if (homeCarousel.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
                logFocusWarn("pendingFocusAnimationRunnable abort | scrollState is not IDLE: "
                        + scrollStateToString(homeCarousel.getScrollState()));
                return;
            }

            carouselAdapter.setFocusedPosition(targetPosition, true);
        };

        focusAnimationHandler.postDelayed(
                pendingFocusAnimationRunnable,
                FOCUS_ANIMATION_START_DELAY_MS
        );

        logFocus("scheduleFocusAnimationAfterDelay posted | target=" + adapterPositionInfo(adapterPosition));
    }

    @Override
    public void cancelPendingFocusAnimation() {
        if (pendingFocusAnimationRunnable != null) {
            logFocus("cancelPendingFocusAnimation | remove pending=" + adapterPositionInfo(pendingFocusPosition));
            focusAnimationHandler.removeCallbacks(pendingFocusAnimationRunnable);
            pendingFocusAnimationRunnable = null;
        } else if (pendingFocusPosition != RecyclerView.NO_POSITION) {
            logFocusWarn("cancelPendingFocusAnimation | runnable null but pending position exists="
                    + adapterPositionInfo(pendingFocusPosition));
        }

        pendingFocusPosition = RecyclerView.NO_POSITION;
    }

    @Override
    public int getLoopStartPosition(int realIndex) {
        int itemSize = cardItems.size();
        if (itemSize <= 0) {
            return 0;
        }

        int middle = LOOP_ITEM_COUNT / 2;
        int offset = middle % itemSize;
        return middle - offset + realIndex;
    }

    private void scrollFocusedItemToCenterForInitialLayout(int position) {
        int focusedStartOffset = dpToPx((SLIDE_WIDTH_DP - FOCUS_WIDTH_DP) / 2);
        carouselLayoutManager.scrollToPositionWithOffset(position, focusedStartOffset);
    }

    private int findNearestPositionToCenter() {
        if (homeCarousel == null || carouselLayoutManager == null) {
            logFocusWarn("findNearestPositionToCenter abort | homeCarousel or layoutManager is null");
            return RecyclerView.NO_POSITION;
        }

        int childCount = homeCarousel.getChildCount();
        if (childCount <= 0) {
            logFocusWarn("findNearestPositionToCenter abort | childCount=0, width=" + homeCarousel.getWidth()
                    + ", scrollState=" + scrollStateToString(homeCarousel.getScrollState()));
            return RecyclerView.NO_POSITION;
        }

        int recyclerCenterX = homeCarousel.getWidth() / 2;
        int nearestPosition = RecyclerView.NO_POSITION;
        int nearestDistance = Integer.MAX_VALUE;

        logFocus("findNearestPositionToCenter start | childCount=" + childCount
                + ", recyclerWidth=" + homeCarousel.getWidth()
                + ", recyclerCenterX=" + recyclerCenterX
                + ", firstVisible=" + carouselLayoutManager.findFirstVisibleItemPosition()
                + ", lastVisible=" + carouselLayoutManager.findLastVisibleItemPosition());

        for (int i = 0; i < childCount; i++) {
            View child = homeCarousel.getChildAt(i);
            int adapterPosition = homeCarousel.getChildAdapterPosition(child);
            int childCenterX = (child.getLeft() + child.getRight()) / 2;
            int distance = Math.abs(childCenterX - recyclerCenterX);

            logFocus("findNearest child | index=" + i
                    + ", " + adapterPositionInfo(adapterPosition)
                    + ", left=" + child.getLeft()
                    + ", right=" + child.getRight()
                    + ", width=" + child.getWidth()
                    + ", centerX=" + childCenterX
                    + ", distance=" + distance
                    + ", attached=" + child.isAttachedToWindow());

            if (adapterPosition == RecyclerView.NO_POSITION) {
                logFocusWarn("findNearest child ignored because adapterPosition is NO_POSITION | index=" + i);
                continue;
            }

            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestPosition = adapterPosition;
            }
        }

        logFocus("findNearestPositionToCenter result | nearest=" + adapterPositionInfo(nearestPosition)
                + ", nearestDistance=" + nearestDistance);

        return nearestPosition;
    }

    @Override
    public int getCarouselScrollState() {
        return homeCarousel == null ? RecyclerView.SCROLL_STATE_IDLE : homeCarousel.getScrollState();
    }

    @Override
    public void stopCarouselScroll() {
        if (homeCarousel != null) {
            homeCarousel.stopScroll();
        }

        cancelCenterScrollAnimation();
    }

    @Override
    public void scrollCarouselBy(int dx) {
        if (homeCarousel != null) {
            homeCarousel.scrollBy(dx, 0);
        }
    }

    @Override
    protected void onDestroy() {
        cancelPendingFocusAnimation();
        cancelCenterScrollAnimation();

        if (homeCardDataRepository != null) {
            homeCardDataRepository.stop();
            homeCardDataRepository = null;
        }

        super.onDestroy();
    }

    @Override
    public int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void logFocus(String message) {
        Log.d(TAG, message);
    }

    @Override
    public void logFocusWarn(String message) {
        Log.w(TAG, message);
    }

    @Override
    public String scrollStateToString(int state) {
        if (state == RecyclerView.SCROLL_STATE_IDLE) {
            return "IDLE";
        }

        if (state == RecyclerView.SCROLL_STATE_DRAGGING) {
            return "DRAGGING";
        }

        if (state == RecyclerView.SCROLL_STATE_SETTLING) {
            return "SETTLING";
        }

        return "UNKNOWN(" + state + ")";
    }

    @Override
    public String adapterPositionInfo(int adapterPosition) {
        if (adapterPosition == RecyclerView.NO_POSITION) {
            return "NO_POSITION";
        }

        int itemSize = cardItems == null ? 0 : cardItems.size();
        if (itemSize <= 0) {
            return "adapter=" + adapterPosition + ", real=N/A, itemSize=" + itemSize;
        }

        int realPosition = adapterPosition % itemSize;
        if (realPosition < 0) {
            realPosition += itemSize;
        }

        return "adapter=" + adapterPosition + ", real=" + realPosition + ", itemSize=" + itemSize;
    }

    @Override
    public void beginFocusTransitionAnimator() {
        runningFocusAnimatorCount++;
        isFocusTransitionLocked = true;

        logFocus("beginFocusTransitionAnimator | running=" + runningFocusAnimatorCount
                + ", locked=" + isFocusTransitionLocked
                + ", scrollState=" + scrollStateToString(homeCarousel == null
                ? -1 : homeCarousel.getScrollState()));
    }

    @Override
    public void finishFocusTransitionAnimator() {
        runningFocusAnimatorCount--;

        if (runningFocusAnimatorCount <= 0) {
            runningFocusAnimatorCount = 0;
            isFocusTransitionLocked = false;
        }

        logFocus("finishFocusTransitionAnimator | running=" + runningFocusAnimatorCount
                + ", locked=" + isFocusTransitionLocked
                + ", scrollState=" + scrollStateToString(homeCarousel == null
                ? -1 : homeCarousel.getScrollState()));
    }

    @Override
    public boolean isFocusTransitionLocked() {
        return isFocusTransitionLocked;
    }

    @Override
    public void onMediaPlayPauseClicked(@NonNull HomeCardItem item, int adapterPosition) {
        logFocus("onMediaPlayPauseClicked | type=" + item.type
                + ", adapterPosition=" + adapterPosition
                + ", item=" + adapterPositionInfo(adapterPosition));

        if (homeCardDataRepository == null) {
            logFocusWarn("onMediaPlayPauseClicked ignored | repository is null");
            return;
        }

        if (item.type == HomeCardItem.TYPE_MUSIC || item.type == HomeCardItem.TYPE_VIDEO) {
            homeCardDataRepository.performPlayPause(item.type);
        }
    }

    @Override
    public void onMediaPreviousClicked(@NonNull HomeCardItem item, int adapterPosition) {
        logFocus("onMediaPreviousClicked | type=" + item.type
                + ", adapterPosition=" + adapterPosition
                + ", item=" + adapterPositionInfo(adapterPosition));

        if (homeCardDataRepository == null) {
            logFocusWarn("onMediaPreviousClicked ignored | repository is null");
            return;
        }

        if (item.type == HomeCardItem.TYPE_MUSIC) {
            homeCardDataRepository.performPrevious(item.type);
        }
    }

    @Override
    public void onMediaNextClicked(@NonNull HomeCardItem item, int adapterPosition) {
        logFocus("onMediaNextClicked | type=" + item.type
                + ", adapterPosition=" + adapterPosition
                + ", item=" + adapterPositionInfo(adapterPosition));

        if (homeCardDataRepository == null) {
            logFocusWarn("onMediaNextClicked ignored | repository is null");
            return;
        }

        if (item.type == HomeCardItem.TYPE_MUSIC) {
            homeCardDataRepository.performNext(item.type);
        }
    }
}