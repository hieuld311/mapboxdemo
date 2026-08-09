package fauto.car.navigation;

oneway interface IFautoCarNavigationEventListener {
    void onResult(in String data);

    void onError(in String data);

    void onSearchNearByCategory(in String data);

    void onNavigationStateChanged(in String data);

    // NAV-006 out of scope: demo-mode changes are delivered by AiSettingEventListener.
    // void onNavigationDemoModeChanged(in int mode);

    void onRouteChanged(in String data);
}
