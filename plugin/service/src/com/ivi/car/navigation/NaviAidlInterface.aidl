package com.ivi.car.navigation;

import com.ivi.car.navigation.INaviListener;

interface NaviAidlInterface {
    void registerListener(INaviListener listener);
    void unregisterListener(INaviListener listener);
    void sendNaviData(String data);

    int setRoute(String destination);
    String searchNearBy(int category, int limit, int sortBy);
    int selectSuggestion(String suggestionId);
    String getNavigationState();
    int setMapStyle(int style);
    int startNavigatingHome();
}
