// MapWidgetSurfaceInterface.aidl
package com.ivi.car.navigation;

import android.os.Bundle;
import android.os.IBinder;

interface MapWidgetSurfaceInterface {
    const String KEY_SURFACE_PACKAGE = "surfacePackage";

    Bundle requestMapSurface(IBinder hostToken, int displayId, int widthPx, int heightPx, IBinder clientToken);

    oneway void releaseMapSurface(IBinder clientToken);
}