package fauto.car.navigation;

oneway interface IFautoCarNavigationEventListener {
    void onResult(in String data);

    void onError(in String data);

    void onSearchNearByCategory(in String data);

    void onNavigationStateChanged(in String data);

    void onNavigationDemoModeChanged(in int mode);

    void onRouteChanged(in String data);
}
