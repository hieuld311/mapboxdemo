// MapWidgetSurfaceInterface.aidl
package com.ivi.car.navigation;

// New, isolated AIDL surface separate from NaviAidlInterface: hands a live, read-only map
// SurfacePackage to a widget host (e.g. the launcher's home card) via SurfaceControlViewHost.
// API 32+ only. Does not touch NaviAidlInterface/NaviAidlService in any way.
interface MapWidgetSurfaceInterface {
    // Bundle key under which the returned Bundle carries an
    // android.window.SurfaceControlViewHost.SurfacePackage (wrapped in a Bundle rather than
    // returned directly, since AIDL cannot cleanly reference that nested framework type).
    const String KEY_SURFACE_PACKAGE = "surfacePackage";

    /**
     * Requests a live map surface for embedding into the caller's SurfaceView.
     * hostToken must come from SurfaceView#getHostToken(); displayId from
     * SurfaceView#getDisplay()#getDisplayId(); widthPx/heightPx from the SurfaceView's
     * measured size. clientToken is a caller-owned IBinder identity (e.g. a fresh Binder())
     * used to key/release this session and to detect the caller's process dying.
     *
     * Returns a Bundle containing KEY_SURFACE_PACKAGE on success, or an empty Bundle if the
     * surface could not be created (below API 32, no such display, or invalid size).
     */
    Bundle requestMapSurface(IBinder hostToken, int displayId, int widthPx, int heightPx, IBinder clientToken);

    /**
     * Releases the session associated with clientToken. Must be called when the widget card
     * is detached or recycled, to avoid leaking the embedded MapView renderer.
     */
    oneway void releaseMapSurface(IBinder clientToken);
}
