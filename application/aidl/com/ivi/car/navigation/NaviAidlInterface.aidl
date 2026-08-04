// NaviAidlInterface.aidl
package com.ivi.car.navigation;

import com.ivi.car.navigation.INaviListener;

interface NaviAidlInterface {
    void registerListener(INaviListener listener);
    void unregisterListener(INaviListener listener);
    void sendNaviData(String data);

    int setRoute(String destination);
    // limit <= 0 uses the application default (5); sortBy == 0 means unspecified.
    String searchNearBy(int category, int limit, int sortBy);
    int selectSuggestion(String suggestionId);
    String getNavigationState();
    int setNavigationDemoMode(int mode);
    int startNavigatingHome();
}
