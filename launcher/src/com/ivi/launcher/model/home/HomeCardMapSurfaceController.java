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
import android.view.SurfaceControlViewHost;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ivi.car.navigation.MapWidgetSurfaceInterface;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controller: requests a live, read-only map surface from the navigation app's
 * MapWidgetSurfaceService and embeds it into a SurfaceView via SurfaceControlViewHost
 * (API 32+ only). Entirely separate from HomeNaviDataProvider's existing map-snapshot Bitmap
 * channel, which is left untouched and stays visible underneath as a fallback.
 *
 * bind()/release() are idempotent and safe to call repeatedly across RecyclerView rebinds and
 * recycles (HomeCarouselAdapter is not modified - this controller is driven entirely from
 * HomeCardViewHolder, using itemView's own attach-state as the recycle signal).
 *
 * HomeCarouselAdapter.setItems() calls notifyDataSetChanged() on every card data update
 * (e.g. every maneuver-text change), which - with no stable IDs - makes RecyclerView's default
 * ItemAnimator detach/reattach bound item views even though nothing about the nav card's type
 * or position changed. releaseDebounced()/cancelPendingRelease() absorb that churn so a
 * detach/reattach blip doesn't tear down and re-request the embedded surface on every update;
 * only a release that survives RELEASE_DEBOUNCE_MS (a real scroll-away recycle) actually runs.
 *
 * requestMapSurface() is a synchronous Binder call, and the nav app does real work (creating a
 * MapView, loading a style, building a SurfaceControlViewHost) on its own main thread before
 * replying - so attachIfReady() dispatches that call to mIoExecutor instead of running it on
 * this (launcher) main thread, and hops back to the main thread only for the SurfaceView calls
 * that require it (setChildSurfacePackage(), and the mAttachedSurfaceView/mPendingSurfaceView
 * state below, which is otherwise only ever touched from the main thread).
 *
 * mRequestInFlight guards against a real race: if bind() is called again (e.g. new TBT data
 * pushes a rebind) while a prior requestMapSurface() round-trip is still outstanding,
 * attachIfReady() would otherwise fire a second concurrent requestMapSurface() call. Since
 * mClientToken is one stable Binder for this controller's whole lifetime, the server
 * (MapWidgetSurfaceService.requestMapSurface()) calls release(clientToken) at the top of every
 * request - so a second concurrent call tears down and rebuilds the session that the first call
 * is still waiting on, causing a visible reload. The guard makes a bind() that arrives mid-flight
 * a no-op; attachIfReady() is retried once the in-flight request's response lands (see the
 * "stale response" branch below), so the retry still happens, just serialized.
 */
public class HomeCardMapSurfaceController {
    private static final String TAG = "HomeCardMapSurfaceCtl";
    private static final String SURFACE_SERVICE_ACTION =
            "com.ivi.car.navigation.service.MapWidgetSurfaceService";
    private static final String NAVI_PACKAGE = "com.ivi.car.navigation";
    private static final long RELEASE_DEBOUNCE_MS = 400L;
    // How long bind() must go quiet (no new SurfaceView reassignment) before we actually
    // request a live surface. Swiping/settling the carousel can reassign the nav card's
    // SurfaceView instance several times in quick succession as RecyclerView recycles/rebinds
    // it - each reassignment would otherwise immediately trigger requestMapSurface(), which
    // tears down whatever session is currently live (see
    // MapWidgetSurfaceService#requestMapSurface, which calls release(clientToken) before
    // building a new session). Debouncing the attach the same way release() is debounced below
    // means we only actually attach once the card's on-screen position/instance has settled,
    // instead of tearing down and rebuilding the Mapbox session mid-swipe.
    private static final long ATTACH_DEBOUNCE_MS = 250L;

    private final Context mContext;
    private final IBinder mClientToken = new Binder();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Handler mReleaseHandler = new Handler(Looper.getMainLooper());
    private final Runnable mReleaseRunnable = this::release;
    private final Runnable mAttachRunnable = this::attachIfReady;
    private final ExecutorService mIoExecutor = Executors.newSingleThreadExecutor();

    @Nullable private MapWidgetSurfaceInterface mService;
    private boolean mBound = false;
    @Nullable private SurfaceView mPendingSurfaceView;
    @Nullable private SurfaceView mAttachedSurfaceView;
    // Main-thread only. True from the moment attachIfReady() dispatches a requestMapSurface()
    // call until its response (success or failure) is handled back on the main thread - see
    // class doc.
    private boolean mRequestInFlight = false;

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
     * the given SurfaceView. Safe to call on every RecyclerView bind - a no-op if already
     * attached to this exact SurfaceView.
     */
    public void bind(@NonNull SurfaceView surfaceView) {
        // Any rebind (including a redundant one for the same SurfaceView, below) means this
        // card is still genuinely in use - cancel a pending debounced release from a prior
        // detach/reattach blip so it doesn't fire out from under an active surface.
        cancelPendingRelease();

        if (Build.VERSION.SDK_INT < 32) {
            return;
        }
        if (surfaceView == mAttachedSurfaceView) {
            // Already showing on this exact SurfaceView - nothing to (re)settle, and no reason
            // to keep a stale debounced attach (for a previous, different SurfaceView) alive.
            mMainHandler.removeCallbacks(mAttachRunnable);
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
            // No live session exists yet at this point, so there is nothing to tear down -
            // onServiceConnected() below attaches immediately once the service connects rather
            // than waiting out the debounce, so the very first attach isn't needlessly delayed.
            return;
        }
        // Debounced: only actually request a live surface once bind() stops being called (i.e.
        // the carousel has stopped reassigning this card's SurfaceView) for ATTACH_DEBOUNCE_MS.
        mMainHandler.removeCallbacks(mAttachRunnable);
        mMainHandler.postDelayed(mAttachRunnable, ATTACH_DEBOUNCE_MS);
    }

    private void attachIfReady() {
        SurfaceView surfaceView = mPendingSurfaceView;
        MapWidgetSurfaceInterface service = mService;
        if (surfaceView == null || service == null || !surfaceView.isAttachedToWindow()) {
            return;
        }
        if (surfaceView == mAttachedSurfaceView) {
            return;
        }
        if (mRequestInFlight) {
            // A request for this (or a prior) SurfaceView is already outstanding - see class
            // doc. Once it resolves, the response handler below retries attachIfReady() so this
            // rebind isn't lost, it's just deferred until the in-flight call finishes.
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

        mRequestInFlight = true;
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
                mRequestInFlight = false;
                // True if this controller was released, or rebound to a different SurfaceView,
                // while the background request was in flight - i.e. a newer bind() arrived and
                // was swallowed by the in-flight guard above. Used below to retry that swallowed
                // rebind now that it's clear, whether this request succeeded or failed - a plain
                // failure with no swallowed rebind is not retried, to avoid spinning against a
                // genuinely unavailable service.
                boolean pendingChanged = mService == null || surfaceView != mPendingSurfaceView
                        || !surfaceView.isAttachedToWindow();
                if (finalPkg == null) {
                    Log.w(TAG, "attachIfReady: no surface package returned");
                    if (pendingChanged) {
                        attachIfReady();
                    }
                    return;
                }
                // The response may arrive after this controller was released or rebound to a
                // different SurfaceView while the background request was in flight - drop a
                // stale response instead of attaching it to the wrong (or a torn-down) surface.
                if (pendingChanged) {
                    Log.w(TAG, "attachIfReady: dropping stale surface package response");
                    attachIfReady();
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
     * rather than a real recycle - e.g. itemView's OnAttachStateChangeListener.
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
     * different card type in ensureCardLayout()) - for a plain itemView detach, prefer
     * releaseDebounced() instead, see class doc.
     */
    public void release() {
        cancelPendingRelease();
        mMainHandler.removeCallbacks(mAttachRunnable);
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
        mRequestInFlight = false;
    }
}