package com.ivi.launcher.model.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceView;
import android.window.SurfaceControlViewHost;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ivi.car.navigation.MapWidgetSurfaceInterface;

/**
 * New, isolated controller: requests a live, read-only map surface from the navigation app's
 * MapWidgetSurfaceService and embeds it into a SurfaceView via SurfaceControlViewHost
 * (API 32+ only). Entirely separate from HomeNaviDataProvider's existing map-snapshot Bitmap
 * channel, which is left untouched and stays visible underneath as a fallback.
 *
 * bind()/release() are idempotent and safe to call repeatedly across RecyclerView rebinds and
 * recycles (HomeCarouselAdapter is not modified — this controller is driven entirely from
 * HomeCardViewHolder, using itemView's own attach-state as the recycle signal).
 */
public class HomeCardMapSurfaceController {
    private static final String TAG = "HomeCardMapSurfaceCtl";
    private static final String SURFACE_SERVICE_ACTION =
            "com.ivi.car.navigation.service.MapWidgetSurfaceService";
    private static final String NAVI_PACKAGE = "com.ivi.car.navigation";

    private final Context mContext;
    private final IBinder mClientToken = new Binder();

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

        try {
            Bundle result = service.requestMapSurface(hostToken, displayId, width, height, mClientToken);
            SurfaceControlViewHost.SurfacePackage pkg = result != null
                    ? result.getParcelable(MapWidgetSurfaceInterface.KEY_SURFACE_PACKAGE)
                    : null;
            if (pkg != null) {
                surfaceView.setChildSurfacePackage(pkg);
                mAttachedSurfaceView = surfaceView;
            } else {
                Log.w(TAG, "attachIfReady: no surface package returned");
            }
        } catch (RemoteException e) {
            Log.e(TAG, "attachIfReady: requestMapSurface failed", e);
        }
    }

    /** Safe to call repeatedly, or when never bound. Must be called on card detach/recycle. */
    public void release() {
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
