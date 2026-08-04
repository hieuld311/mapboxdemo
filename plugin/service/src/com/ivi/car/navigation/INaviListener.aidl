package com.ivi.car.navigation;

interface INaviListener {
    void onNaviDataReceived(String data);
    void onNavigationStateChanged(String stateJson);
}
