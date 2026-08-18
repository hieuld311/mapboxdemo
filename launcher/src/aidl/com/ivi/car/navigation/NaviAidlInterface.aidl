// NaviAidlInterface.aidl
package com.ivi.car.navigation;

import com.ivi.car.navigation.INaviListener;

interface NaviAidlInterface {
    /**
     * Demonstrates some basic types that you can use as parameters
     * and return values in AIDL.
     */
     void registerListener(INaviListener listener);
     void unregisterListener(INaviListener listener);
     void sendNaviData(String data);
}