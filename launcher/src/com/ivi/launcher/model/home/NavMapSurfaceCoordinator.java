package com.ivi.launcher.model.home;

import android.content.Context;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

/**
 * Process-wide singleton owner of the ONE live map-widget surface session (see
 * HomeCardMapSurfaceController, which this wraps unchanged). The home carousel is an infinite
 * loop (HomeCarouselAdapter, LOOP_ITEM_COUNT = Integer.MAX_VALUE) with its own RecycledViewPool,
 * so more than one HomeCardViewHolder instance of TYPE_NAVIGATION can exist at once — only one
 * is ever actually bound/visible in practice, but each previously owned its own
 * HomeCardMapSurfaceController. Routing every bind()/release() through this one shared instance
 * guarantees there is never more than one SurfaceControlViewHost session (one MapView, one style
 * load) at a time, regardless of how many ViewHolder instances the RecyclerView happens to keep
 * around.
 *
 * Deliberately NOT wired through HomeCarouselAdapter.CarouselHost — that interface is nested
 * inside HomeCarouselAdapter.java, which is off-limits to edit for now. HomeCardViewHolder calls
 * this singleton directly instead, and IviLauncher releases it in onDestroy() for real
 * activity-death cleanup. Does not touch HomeCarouselAdapter.java or its CarouselHost contract.
 */
public final class NavMapSurfaceCoordinator {
    private static volatile NavMapSurfaceCoordinator sInstance;

    private final HomeCardMapSurfaceController mController;

    private NavMapSurfaceCoordinator(@NonNull Context appContext) {
        mController = new HomeCardMapSurfaceController(appContext);
    }

    @NonNull
    public static NavMapSurfaceCoordinator getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (NavMapSurfaceCoordinator.class) {
                if (sInstance == null) {
                    sInstance = new NavMapSurfaceCoordinator(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    /** See HomeCardMapSurfaceController#bind(SurfaceView). */
    public void bind(@NonNull SurfaceView surfaceView) {
        mController.bind(surfaceView);
    }

    /** See HomeCardMapSurfaceController#releaseDebounced(). */
    public void releaseDebounced() {
        mController.releaseDebounced();
    }

    /** See HomeCardMapSurfaceController#release(). Call from IviLauncher.onDestroy(). */
    public void release() {
        mController.release();
    }
}
