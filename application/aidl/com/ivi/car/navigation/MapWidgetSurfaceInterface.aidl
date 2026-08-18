// MapWidgetSurfaceInterface.aidl
package com.ivi.car.navigation;


interface MapWidgetSurfaceInterface {
    const String KEY_SURFACE_PACKAGE = "surfacePackage";

    /**
     * Requests a live map surface for embedding into the caller's SurfaceView.
     * hostToken must come from SurfaceView#getHostToken(); displayId from
     * SurfaceView#getDisplay()#getDisplayId(); widthPx/heightPx from the SurfaceView's
     * measured size. clientToken is a caller-owned IBinder identity (e.g. a fresh Binder())
     * used to key/release this session and to detect the caller's process dying.
     *
     * Returns a Bundle containing KEY_SURFACE_PACKAGE on success, or an empty Bundle if the
     * surface could not be created.
     */
    Bundle requestMapSurface(IBinder hostToken, int displayId, int widthPx, int heightPx, IBinder clientToken);

    /**
     * Releases the session associated with clientToken. Must be called when the widget card
     * is detached or recycled, to avoid leaking the embedded MapView renderer.
     */
    oneway void releaseMapSurface(IBinder clientToken);
}