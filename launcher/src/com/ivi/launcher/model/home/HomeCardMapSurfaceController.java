package com.ivi.launcher.model.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceView;
import android.window.SurfaceControlViewHost;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ivi.car.navigation.MapWidgetSurfaceInterface;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * New, isolated controller: requests a live, read-only map surface from the navigation app's
 * MapWidgetSurfaceService and embeds it into a SurfaceView via SurfaceControlViewHost
 * (API 32+ only). Entirely separate from HomeNaviDataProvider's existing map-snapshot Bitmap
 * channel, which is left untouched and stays visible underneath as a fallback.
 *
 * bind()/release() are idempotent and safe to call repeatedly across RecyclerView rebinds and
 * recycles (HomeCarouselAdapter is not modified — this controller is driven entirely from
 * HomeCardViewHolder, using itemView's own attach-state as the recycle signal).
 *
 * HomeCarouselAdapter.setItems() calls notifyDataSetChanged() on every card data update
 * (e.g. every maneuver-text change), which — with no stable IDs — makes RecyclerView's default
 * ItemAnimator detach/reattach bound item views even though nothing about the nav card's type
 * or position changed. releaseDebounced()/cancelPendingRelease() absorb that churn so a
 * detach/reattach blip doesn't tear down and re-request the embedded surface on every update;
 * only a release that survives RELEASE_DEBOUNCE_MS (a real scroll-away recycle) actually runs.
 *
 * requestMapSurface() is a synchronous Binder call, and the nav app does real work (creating a
 * MapView, loading a style, building a SurfaceControlViewHost) on its own main thread before
 * replying — so attachIfReady() dispatches that call to mIoExecutor instead of running it on
 * this (launcher) main thread, and hops back to the main thread only for the SurfaceView calls
 * that require it (setChildSurfacePackage(), and the mAttachedSurfaceView/mPendingSurfaceView
 * state below, which is otherwise only ever touched from the main thread).
 */
public class HomeCardMapSurfaceController {
    private static final String TAG = "HomeCardMapSurfaceCtl";
    private static final String SURFACE_SERVICE_ACTION =
            "com.ivi.car.navigation.service.MapWidgetSurfaceService";
    private static final String NAVI_PACKAGE = "com.ivi.car.navigation";
    private static final long RELEASE_DEBOUNCE_MS = 400L;

    private final Context mContext;
    private final IBinder mClientToken = new Binder();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Handler mReleaseHandler = new Handler(Looper.getMainLooper());
    private final Runnable mReleaseRunnable = this::release;
    private final ExecutorService mIoExecutor = Executors.newSingleThreadExecutor();

    @Nullable private MapWidgetSurfaceInterface mService;
    private boolean mBound = false;
    @Nullable private SurfaceView mPendingSurfaceView;
    @Nullable private SurfaceView mAttachedSurfaceView;

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mService = MapWidgetSurfaceInterface.Stub.asInterface(service);
            attachIfReady();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            mAttachedSurfaceView = null;
        }
    };

    public HomeCardMapSurfaceController(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    /**
     * Requests (or re-requests, if the SurfaceView instance changed) a live map surface for
     * the given SurfaceView. Safe to call on every RecyclerView bind — a no-op if already
     * attached to this exact SurfaceView.
     */
    public void bind(@NonNull SurfaceView surfaceView) {
        // Any rebind (including a redundant one for the same SurfaceView, below) means this
        // card is still genuinely in use — cancel a pending debounced release from a prior
        // detach/reattach blip so it doesn't fire out from under an active surface.
        cancelPendingRelease();

        if (Build.VERSION.SDK_INT < 32) {
            return;
        }
        if (surfaceView == mAttachedSurfaceView) {
            return;
        }
        mPendingSurfaceView = surfaceView;
        if (!mBound) {
            Intent intent = new Intent(SURFACE_SERVICE_ACTION);
            intent.setPackage(NAVI_PACKAGE);
            try {
                mBound = mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
                if (!mBound) {
                    Log.w(TAG, "bind: bindService returned false");
                }
            } catch (Exception e) {
                Log.e(TAG, "bind: bindService exception", e);
            }
            return;
        }
        attachIfReady();
    }

    private void attachIfReady() {
        SurfaceView surfaceView = mPendingSurfaceView;
        MapWidgetSurfaceInterface service = mService;
        if (surfaceView == null || service == null || !surfaceView.isAttachedToWindow()) {
            return;
        }
        IBinder hostToken = surfaceView.getHostToken();
        if (hostToken == null) {
            return;
        }
        int width = surfaceView.getWidth();
        int height = surfaceView.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        Display display = surfaceView.getDisplay();
        int displayId = display != null ? display.getDisplayId() : 0;

        mIoExecutor.execute(() -> {
            SurfaceControlViewHost.SurfacePackage pkg = null;
            try {
                Bundle result =
                        service.requestMapSurface(hostToken, displayId, width, height, mClientToken);
                pkg = result != null
                        ? result.getParcelable(MapWidgetSurfaceInterface.KEY_SURFACE_PACKAGE)
                        : null;
            } catch (RemoteException e) {
                Log.e(TAG, "attachIfReady: requestMapSurface failed", e);
            }
            SurfaceControlViewHost.SurfacePackage finalPkg = pkg;
            mMainHandler.post(() -> {
                if (finalPkg == null) {
                    Log.w(TAG, "attachIfReady: no surface package returned");
                    return;
                }
                // The response may arrive after this controller was released or rebound to a
                // different SurfaceView while the background request was in flight — drop a
                // stale response instead of attaching it to the wrong (or a torn-down) surface.
                if (mService == null || surfaceView != mPendingSurfaceView
                        || !surfaceView.isAttachedToWindow()) {
                    Log.w(TAG, "attachIfReady: dropping stale surface package response");
                    return;
                }
                surfaceView.setChildSurfacePackage(finalPkg);
                mAttachedSurfaceView = surfaceView;
            });
        });
    }

    /**
     * Schedules release() after RELEASE_DEBOUNCE_MS, unless cancelPendingRelease() (via bind())
     * runs first. Use this from a detach signal that might just be transient RecyclerView churn
     * rather than a real recycle — e.g. itemView's OnAttachStateChangeListener.
     */
    public void releaseDebounced() {
        mReleaseHandler.removeCallbacks(mReleaseRunnable);
        mReleaseHandler.postDelayed(mReleaseRunnable, RELEASE_DEBOUNCE_MS);
    }

    /** Cancels a pending releaseDebounced(), if any. Safe to call when none is pending. */
    public void cancelPendingRelease() {
        mReleaseHandler.removeCallbacks(mReleaseRunnable);
    }

    /**
     * Immediately releases the surface. Safe to call repeatedly, or when never bound. Use this
     * for a definite teardown (e.g. the nav card's view tree is about to be discarded for a
     * different card type in ensureCardLayout()) — for a plain itemView detach, prefer
     * releaseDebounced() instead, see class doc.
     */
    public void release() {
        cancelPendingRelease();
        if (mService != null) {
            try {
                mService.releaseMapSurface(mClientToken);
            } catch (RemoteException e) {
                Log.w(TAG, "release: releaseMapSurface failed", e);
            }
        }
        if (mBound) {
            try {
                mContext.unbindService(mConnection);
            } catch (Exception e) {
                Log.w(TAG, "release: unbindService exception", e);
            }
            mBound = false;
        }
        mService = null;
        mPendingSurfaceView = null;
        mAttachedSurfaceView = null;
    }
}
