package fauto.car.navigation;

import fauto.car.navigation.IFautoCarNavigationEventListener;

interface IFAutoCarNavigation {
    oneway void registerFAutoCarNavigationEventListener(
            in IFautoCarNavigationEventListener listener);

    oneway void unregisterFAutoCarNavigationEventListener(
            in IFautoCarNavigationEventListener listener);

    oneway void sendDataTurnByTurn(in String data);

    oneway void getSearchNearbyCategory(in String category, in int limit, in int sortedBy);

    int setRoute(in String destination);

    int selectSuggestion(in String suggestionId);

    String getNavigationState();

    int setNavigationDemoMode(in int mode);

    int startNavigatingHome();
}
