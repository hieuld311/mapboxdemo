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

    int setRoute(String destination);
    // limit <= 0 uses the application default (5); sortBy == 0 means unspecified.
    // Result arrives asynchronously via INaviListener.onSearchNearbyResult.
    oneway void searchNearBy(int category, int limit, int sortBy);
    int selectSuggestion(String suggestionId);
    String getNavigationState();
    int setNavigationDemoMode(int mode);
    int startNavigatingHome();
}