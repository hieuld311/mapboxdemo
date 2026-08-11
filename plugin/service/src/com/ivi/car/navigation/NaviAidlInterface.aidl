// Shared plugin-to-application command contract. Keep this package and method order
// identical to the navigation application's AIDL definition.
package com.ivi.car.navigation;

import com.ivi.car.navigation.INaviListener;

interface NaviAidlInterface {
    void registerListener(INaviListener listener);
    void unregisterListener(INaviListener listener);
    void sendNaviData(String data);
    int setRoute(String destination);
    void searchNearBy(int category, int limit, int sortBy);
    int selectSuggestion(String suggestionId);
    String getNavigationState();
    int startNavigatingHome();
}
