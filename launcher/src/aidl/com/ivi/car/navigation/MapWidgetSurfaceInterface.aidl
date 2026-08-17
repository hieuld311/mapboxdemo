// MapWidgetSurfaceInterface.aidl
package com.ivi.car.navigation;

// Mirrors application/aidl/com/ivi/car/navigation/MapWidgetSurfaceInterface.aidl — launcher
// compiles its own local aidl sources (same pattern as its existing NaviAidlInterface.aidl /
// INaviListener.aidl copies), so this file must stay in sync with the app-side original.
interface MapWidgetSurfaceInterface {
    const String KEY_SURFACE_PACKAGE = "surfacePackage";

    Bundle requestMapSurface(IBinder hostToken, int displayId, int widthPx, int heightPx, IBinder clientToken);

    oneway void releaseMapSurface(IBinder clientToken);
}
