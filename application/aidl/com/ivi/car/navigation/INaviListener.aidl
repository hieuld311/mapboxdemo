// NaviAidlInterface.aidl
package com.ivi.car.navigation;

// Declare any non-default types here with import statements
interface INaviListener {
    void onNaviDataReceived(String data);
    void onNavigationStateChanged(String stateJson);
    void onCommandResult(String data);
    void onCommandError(String data);
    void onRouteChanged(String data);
    void onSearchNearbyResult(String data);
}