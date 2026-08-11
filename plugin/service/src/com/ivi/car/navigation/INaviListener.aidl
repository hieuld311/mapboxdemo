// Shared application-to-plugin callback contract. Keep this package and method order
// identical to the navigation application's AIDL definition.
package com.ivi.car.navigation;

interface INaviListener {
    void onNaviDataReceived(String data);
    void onNavigationStateChanged(String stateJson);
    void onCommandResult(String data);
    void onCommandError(String data);
    void onRouteChanged(String data);
    void onSearchNearbyResult(String data);
}
