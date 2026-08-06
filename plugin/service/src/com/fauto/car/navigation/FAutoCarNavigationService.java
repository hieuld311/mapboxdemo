package com.fauto.car.navigation;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import com.fauto.car.base.BinderInterfaceContainer;
import com.fauto.car.base.FAutoCarServiceBase;
import com.fauto.car.base.FAutoCarServiceHelper;
import com.ivi.car.navigation.INaviListener;
import com.ivi.car.navigation.NaviAidlInterface;

import java.io.PrintWriter;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import fauto.car.navigation.FAutoCarNavigationManager;
import fauto.car.navigation.IFAutoCarNavigation;
import fauto.car.navigation.IFautoCarNavigationEventListener;

public class FAutoCarNavigationService extends IFAutoCarNavigation.Stub
        implements FAutoCarServiceBase {
    private static final String LOG_TAG = "FAutoCarNavigationService";

    private static final String NAVIGATION_APP_PACKAGE = "com.ivi.car.navigation";
    private static final String NAVIGATION_APP_SERVICE =
            "com.ivi.car.navigation.service.NaviAidlService";
    private static final String NAVIGATION_APP_BIND_ACTION =
            "com.ivi.car.navigation.service.NaviAIDLService";

    private Context mContext;
    private HandlerThread mHandlerThread;
    private Handler mEventHandler;

    private final BinderInterfaceContainer<IFautoCarNavigationEventListener> mFAutoCarNavigation =
            new BinderInterfaceContainer<>();

    public static final int MSG_ON_SEND_DATA_TURN_BY_TURN = 0xA000;
    public static final int MSG_ON_GET_SEARCH_NEAR_BY = 0xA001;

    private int mNavigationState = FAutoCarNavigationManager.NAVIGATION_STATE_IDLE;
    private int mNavigationDemoMode = FAutoCarNavigationManager.NAVIGATION_DEMO_MODE_NORMAL;

    private String mLastDestination = "";
    private String mLastSuggestionId = "";
    private String mLastSearchCategory = "";
    private int mLastSearchLimit = 0;
    private int mLastSearchSortedBy = FAutoCarNavigationManager.SORT_BY_DISTANCE;
    private final Object mNavigationAppLock = new Object();
    private NaviAidlInterface mNavigationAppService;
    private boolean mNavigationAppBound;
    private volatile String mLastNavigationAppStateJson;
    private int mLastForwardedRouteState = FAutoCarNavigationManager.NAVIGATION_STATE_UNAVAILABLE;

    private final INaviListener mNavigationAppListener = new INaviListener.Stub() {
        @Override
        public void onNaviDataReceived(String data) {
            handleNavigationAppData(data);
        }

        @Override
        public void onNavigationStateChanged(String stateJson) {
            publishNavigationAppState(stateJson);
        }
    };

    private final ServiceConnection mNavigationAppConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mNavigationAppLock) {
                mNavigationAppService = NaviAidlInterface.Stub.asInterface(service);
            }
            try {
                NaviAidlInterface navigationApp = getNavigationAppService();
                if (navigationApp != null) {
                    navigationApp.registerListener(mNavigationAppListener);
                    publishNavigationAppState(navigationApp.getNavigationState());
                }
            } catch (RemoteException error) {
                Log.e(LOG_TAG, "Unable to register app navigation listener", error);
                clearNavigationAppService();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            clearNavigationAppService();
        }
    };

    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                switch (msg.what) {
                    case MSG_ON_SEND_DATA_TURN_BY_TURN:
                        sendTurnByTurn((String) msg.obj);
                        break;

                    case MSG_ON_GET_SEARCH_NEAR_BY:
                        handleSearchNearbyCategory((String) msg.obj, msg.arg2, msg.arg1);
                        break;

                    default:
                        Log.i(LOG_TAG, "Unexpected message");
                        break;
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Exception occurred when Handle message", e);
            }
        }
    }

    public FAutoCarNavigationService(Context context) {
        Log.d(LOG_TAG, "Constructor");
        mContext = context;
        mHandlerThread = new HandlerThread("FAUTO-NAVIGATION-SERVICE-EVENT");
        mHandlerThread.start();
        mEventHandler = new ServiceHandler(mHandlerThread.getLooper());
    }

    @Override
    public void init(FAutoCarServiceHelper fAutoCarServiceHelper) {
        Log.d(LOG_TAG, "init");
        bindNavigationApp();
    }

    @Override
    public void release() {
        Log.d(LOG_TAG, "release");

        unbindNavigationApp();

        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            mHandlerThread = null;
            mEventHandler = null;
        }
    }

    private void bindNavigationApp() {
        if (mContext == null) {
            return;
        }
        synchronized (mNavigationAppLock) {
            if (mNavigationAppBound) {
                return;
            }
        }
        Intent intent = new Intent(NAVIGATION_APP_BIND_ACTION)
                .setComponent(new ComponentName(NAVIGATION_APP_PACKAGE, NAVIGATION_APP_SERVICE));
        try {
            boolean bound = mContext.bindService(intent, mNavigationAppConnection, Context.BIND_AUTO_CREATE);
            synchronized (mNavigationAppLock) {
                mNavigationAppBound = bound;
            }
            if (!bound) {
                publishUnavailableState("Navigation app is unavailable");
            }
        } catch (SecurityException error) {
            Log.e(LOG_TAG, "Unable to bind navigation app", error);
            publishUnavailableState("Navigation app bind permission was denied");
        }
    }

    private void unbindNavigationApp() {
        if (mContext == null) {
            return;
        }
        NaviAidlInterface navigationApp = getNavigationAppService();
        if (navigationApp != null) {
            try {
                navigationApp.unregisterListener(mNavigationAppListener);
            } catch (RemoteException error) {
                Log.w(LOG_TAG, "Unable to unregister app navigation listener", error);
            }
        }
        boolean bound;
        synchronized (mNavigationAppLock) {
            bound = mNavigationAppBound;
            mNavigationAppBound = false;
            mNavigationAppService = null;
        }
        if (bound) {
            try {
                mContext.unbindService(mNavigationAppConnection);
            } catch (IllegalArgumentException error) {
                Log.w(LOG_TAG, "Navigation app was already unbound", error);
            }
        }
    }

    private NaviAidlInterface getNavigationAppService() {
        synchronized (mNavigationAppLock) {
            return mNavigationAppService;
        }
    }

    private void clearNavigationAppService() {
        synchronized (mNavigationAppLock) {
            mNavigationAppService = null;
        }
        publishUnavailableState("Navigation app disconnected");
    }

    @Override
    public String getManagerName() {
        return FAutoCarNavigationManager.class.getName();
    }

    @Override
    public String getServiceName() {
        return FAutoCarNavigationManager.NAVIGATION_SERVICE;
    }

    @Override
    public String getServicePermission() {
        return FAutoCarNavigationManager.PERMISSION_NAVIGATION;
    }

    @Override
    public void dump(PrintWriter printWriter) {
    }

    @Override
    public void registerFAutoCarNavigationEventListener(
            IFautoCarNavigationEventListener listener) throws RemoteException {
        if (listener == null) {
            Log.w(LOG_TAG, "registerFAutoCarNavigationEventListener ignored: listener is null");
            return;
        }

        synchronized (mFAutoCarNavigation) {
            if (mFAutoCarNavigation.getBinderInterface(listener) == null) {
                mFAutoCarNavigation.addBinder(listener);
            }
        }

        notifyCurrentNavigationDataToClient(listener);
    }

    @Override
    public void unregisterFAutoCarNavigationEventListener(
            IFautoCarNavigationEventListener listener) throws RemoteException {
        if (listener == null) {
            Log.w(LOG_TAG, "unregisterFAutoCarNavigationEventListener ignored: listener is null");
            return;
        }

        synchronized (mFAutoCarNavigation) {
            if (mFAutoCarNavigation.getBinderInterface(listener) != null) {
                mFAutoCarNavigation.removeBinder(listener);
            }
        }
    }

    @Override
    public void sendDataTurnByTurn(String data) throws RemoteException {
        if (mEventHandler == null) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    "Event handler is null",
                    "sendDataTurnByTurn"));
            return;
        }

        mEventHandler.sendMessage(
                Message.obtain(mEventHandler, MSG_ON_SEND_DATA_TURN_BY_TURN, data));
    }

    @Override
    public void getSearchNearbyCategory(String category, int limit, int sortedBy)
            throws RemoteException {
        if (mEventHandler == null) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    "Event handler is null",
                    "getSearchNearbyCategory"));
            return;
        }

        mEventHandler.sendMessage(
                Message.obtain(
                        mEventHandler,
                        MSG_ON_GET_SEARCH_NEAR_BY,
                        sortedBy,
                        limit,
                        category
                ));
    }

    @Override
    public int setRoute(String destination) throws RemoteException {
        Log.i(LOG_TAG, "setRoute: " + destination);

        if (isEmpty(destination)) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_VALUE_INVALID,
                    "Destination is empty",
                    "setRoute"));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        NaviAidlInterface navigationApp = getNavigationAppService();
        if (navigationApp == null) {
            bindNavigationApp();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    "Navigation app is unavailable",
                    "setRoute"));
            return FAutoCarNavigationManager.ERROR_UNAVAILABLE;
        }

        try {
            int appResult = navigationApp.setRoute(destination);
            if (appResult != 0) {
                handleAppCommandError("setRoute", appResult);
            }
            return mapAppResultCode(appResult);
        } catch (RemoteException error) {
            handleAppRemoteException("setRoute", error);
            return FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION;
        }
    }

    @Override
    public int selectSuggestion(String suggestionId) throws RemoteException {
        Log.i(LOG_TAG, "selectSuggestion: " + suggestionId);

        if (isEmpty(suggestionId)) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_VALUE_INVALID,
                    "Suggestion id is empty",
                    "selectSuggestion"));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        NaviAidlInterface navigationApp = getNavigationAppService();
        if (navigationApp == null) {
            bindNavigationApp();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    "Navigation app is unavailable",
                    "selectSuggestion"));
            return FAutoCarNavigationManager.ERROR_UNAVAILABLE;
        }

        try {
            int appResult = navigationApp.selectSuggestion(suggestionId);
            if (appResult != 0) {
                handleAppCommandError("selectSuggestion", appResult);
            }
            return mapAppResultCode(appResult);
        } catch (RemoteException error) {
            handleAppRemoteException("selectSuggestion", error);
            return FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION;
        }
    }

    @Override
    public String getNavigationState() throws RemoteException {
        NaviAidlInterface navigationApp = getNavigationAppService();
        if (navigationApp != null) {
            try {
                return translateNavigationAppState(navigationApp.getNavigationState());
            } catch (RemoteException error) {
                Log.w(LOG_TAG, "Unable to query navigation app state", error);
                synchronized (mNavigationAppLock) {
                    mNavigationAppService = null;
                }
            }
        }
        String navigationState = buildUnavailableStateJson();
        Log.i(LOG_TAG, "getNavigationState: " + navigationState);
        return navigationState;
    }

    @Override
    public int setNavigationDemoMode(int mode) throws RemoteException {
        Log.i(LOG_TAG, "setNavigationDemoMode: " + mode);

        if (!isValidNavigationDemoMode(mode)) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_VALUE_INVALID,
                    "Invalid navigation demo mode: " + mode,
                    "setNavigationDemoMode"));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        NaviAidlInterface navigationApp = getNavigationAppService();
        if (navigationApp == null) {
            bindNavigationApp();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    "Navigation app is unavailable",
                    "setNavigationDemoMode"));
            return FAutoCarNavigationManager.ERROR_UNAVAILABLE;
        }

        try {
            int appResult = navigationApp.setNavigationDemoMode(mode);
            if (appResult != 0) {
                handleAppCommandError("setNavigationDemoMode", appResult);
            }
            return mapAppResultCode(appResult);
        } catch (RemoteException error) {
            handleAppRemoteException("setNavigationDemoMode", error);
            return FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION;
        }
    }

    @Override
    public int startNavigatingHome() throws RemoteException {
        Log.i(LOG_TAG, "startNavigatingHome");

        NaviAidlInterface navigationApp = getNavigationAppService();
        if (navigationApp == null) {
            bindNavigationApp();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    "Navigation app is unavailable",
                    "startNavigatingHome"));
            return FAutoCarNavigationManager.ERROR_UNAVAILABLE;
        }

        try {
            int appResult = navigationApp.startNavigatingHome();
            if (appResult != 0) {
                handleAppCommandError("startNavigatingHome", appResult);
            }
            return mapAppResultCode(appResult);
        } catch (RemoteException error) {
            handleAppRemoteException("startNavigatingHome", error);
            return FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION;
        }
    }

    private int sendTurnByTurn(String data) {
        Log.i(LOG_TAG, "sendTurnByTurn: " + data);

        if (isEmpty(data)) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_VALUE_INVALID,
                    "Turn-by-turn data is empty",
                    "sendDataTurnByTurn"));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        NaviAidlInterface navigationApp = getNavigationAppService();
        if (navigationApp == null) {
            bindNavigationApp();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    "Navigation app is unavailable",
                    "sendDataTurnByTurn"));
            return FAutoCarNavigationManager.ERROR_UNAVAILABLE;
        }

        try {
            navigationApp.sendNaviData(data);
        } catch (RemoteException error) {
            Log.e(LOG_TAG, "sendNaviData RemoteException", error);
            clearNavigationAppService();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION,
                    "Navigation app connection failed",
                    "sendDataTurnByTurn"));
            return FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION;
        }
        return FAutoCarNavigationManager.RESULT_OK;
    }

    private int handleSearchNearbyCategory(String category, int limit, int sortedBy) {
        Log.i(LOG_TAG, "handleSearchNearbyCategory: category=" + category
                + ", limit=" + limit
                + ", sortedBy=" + sortedBy);

        if (isEmpty(category)) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_VALUE_INVALID,
                    "Category is empty",
                    "getSearchNearbyCategory"));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        if (limit <= 0) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_VALUE_INVALID,
                    "Invalid limit: " + limit,
                    "getSearchNearbyCategory"));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        if (!isValidSortBy(sortedBy)) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_VALUE_INVALID,
                    "Invalid sortedBy: " + sortedBy,
                    "getSearchNearbyCategory"));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        int appCategory = mapCategoryToAppCode(category);
        if (appCategory == 0) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_VALUE_INVALID,
                    "Unsupported category: " + category,
                    "getSearchNearbyCategory"));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        NaviAidlInterface navigationApp = getNavigationAppService();
        if (navigationApp == null) {
            bindNavigationApp();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    "Navigation app is unavailable",
                    "getSearchNearbyCategory"));
            return FAutoCarNavigationManager.ERROR_UNAVAILABLE;
        }

        mLastSearchCategory = category;
        mLastSearchLimit = limit;
        mLastSearchSortedBy = sortedBy;
        try {
            String result = navigationApp.searchNearBy(
                    appCategory,
                    limit,
                    mapSortToAppCode(sortedBy));
            forwardSearchNearbyResult(result, category, limit, sortedBy);
            return FAutoCarNavigationManager.RESULT_OK;
        } catch (RemoteException error) {
            Log.e(LOG_TAG, "searchNearBy RemoteException", error);
            clearNavigationAppService();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION,
                    "Navigation app connection failed",
                    "getSearchNearbyCategory"));
            return FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION;
        }
    }

    private void handleNavigationAppData(String data) {
        try {
            JSONObject event = new JSONObject(data);
            if (!"navigation-command".equals(event.optString("channel"))) {
                handleResult(data);
                return;
            }

            String api = event.optString("api", "navigation");
            int appResultCode = event.optInt("resultCode", -7);
            String message = event.optString("message", "Navigation command failed");
            if ("ERROR".equals(event.optString("type"))) {
                handleError(buildAppErrorJson(api, appResultCode, message));
                return;
            }

            JSONObject result = new JSONObject();
            result.put("api", api);
            result.put("resultCode", mapAppResultCode(appResultCode));
            result.put("appResultCode", appResultCode);
            result.put("message", message);
            JSONObject destination = event.optJSONObject("destination");
            if (destination != null) {
                result.put("destination", destination);
            }
            handleResult(result.toString());
        } catch (JSONException ignored) {
            // Turn-by-turn data is intentionally opaque and must remain pass-through.
            handleResult(data);
        }
    }

    private void forwardSearchNearbyResult(
            String appResultJson,
            String category,
            int limit,
            int sortedBy) {
        try {
            JSONObject appResult = new JSONObject(appResultJson);
            int appResultCode = appResult.optInt("resultCode", -7);
            if (appResultCode != 0) {
                handleError(buildAppErrorJson(
                        "getSearchNearbyCategory",
                        appResultCode,
                        appResult.optString("message", "Nearby search failed")));
                return;
            }
            handleSearchNearByDataToClient(
                    buildSearchNearbyResult(appResult, category, limit, sortedBy));
        } catch (Exception error) {
            Log.e(LOG_TAG, "Invalid search result from navigation app", error);
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_OPERATION_FAILED,
                    "Navigation app returned an invalid search result",
                    "getSearchNearbyCategory"));
        }
    }

    private void publishNavigationAppState(String appStateJson) {
        if (isEmpty(appStateJson)) {
            publishUnavailableState("Navigation app returned an empty state");
            return;
        }
        try {
            JSONObject appState = new JSONObject(appStateJson);
            String status = appState.optString("status", "UNAVAILABLE");
            int state = mapAppState(status);
            JSONObject destination = appState.optJSONObject("destination");
            int appDemoMode = appState.optInt("demoMode", mNavigationDemoMode);

            mNavigationState = state;
            if (destination != null) {
                mLastDestination = destination.optString("name", "");
                mLastSuggestionId = destination.optString("suggestionId", "");
            } else {
                mLastDestination = "";
                mLastSuggestionId = "";
            }
            if (isValidNavigationDemoMode(appDemoMode) && appDemoMode != mNavigationDemoMode) {
                mNavigationDemoMode = appDemoMode;
                handleNavigationDemoModeChanged(appDemoMode);
            }

            JSONObject pluginState = new JSONObject();
            pluginState.put("state", state);
            pluginState.put("stateName", getNavigationStateName(state));
            pluginState.put("destination", mLastDestination);
            pluginState.put("suggestionId", mLastSuggestionId);
            pluginState.put("demoMode", mNavigationDemoMode);
            pluginState.put("demoModeName", getNavigationDemoModeName(mNavigationDemoMode));
            pluginState.put("lastSearchCategory", mLastSearchCategory);
            pluginState.put("lastSearchLimit", mLastSearchLimit);
            pluginState.put("lastSearchSortedBy", mLastSearchSortedBy);
            pluginState.put("lastSearchSortedByName", getSortByName(mLastSearchSortedBy));
            pluginState.put("appState", appState);
            mLastNavigationAppStateJson = pluginState.toString();
            handleNavigationStateChanged(mLastNavigationAppStateJson);

            if (state == FAutoCarNavigationManager.NAVIGATION_STATE_ROUTE_SET
                    && state != mLastForwardedRouteState) {
                handleRouteChanged(buildRouteDataJson(mLastDestination, mLastSuggestionId));
            }
            mLastForwardedRouteState = state;
        } catch (JSONException error) {
            Log.e(LOG_TAG, "Invalid navigation app state", error);
            publishUnavailableState("Navigation app returned an invalid state");
        }
    }

    /**
     * Converts an application state response for the synchronous query API without
     * notifying registered listeners. getNavigationState() is intentionally read-only.
     */
    private String translateNavigationAppState(String appStateJson) {
        if (isEmpty(appStateJson)) {
            return buildUnavailableStateJson();
        }
        try {
            JSONObject appState = new JSONObject(appStateJson);
            int state = mapAppState(appState.optString("status", "UNAVAILABLE"));
            JSONObject destination = appState.optJSONObject("destination");
            int demoMode = appState.optInt("demoMode", mNavigationDemoMode);
            if (!isValidNavigationDemoMode(demoMode)) {
                demoMode = FAutoCarNavigationManager.NAVIGATION_DEMO_MODE_NORMAL;
            }

            JSONObject pluginState = new JSONObject();
            pluginState.put("state", state);
            pluginState.put("stateName", getNavigationStateName(state));
            pluginState.put("destination", destination != null
                    ? destination.optString("name", "") : "");
            pluginState.put("suggestionId", destination != null
                    ? destination.optString("suggestionId", "") : "");
            pluginState.put("demoMode", demoMode);
            pluginState.put("demoModeName", getNavigationDemoModeName(demoMode));
            pluginState.put("lastSearchCategory", mLastSearchCategory);
            pluginState.put("lastSearchLimit", mLastSearchLimit);
            pluginState.put("lastSearchSortedBy", mLastSearchSortedBy);
            pluginState.put("lastSearchSortedByName", getSortByName(mLastSearchSortedBy));
            pluginState.put("appState", appState);
            return pluginState.toString();
        } catch (JSONException error) {
            Log.e(LOG_TAG, "Invalid navigation app state", error);
            return buildUnavailableStateJson();
        }
    }

    private void publishUnavailableState(String message) {
        mNavigationState = FAutoCarNavigationManager.NAVIGATION_STATE_UNAVAILABLE;
        mLastForwardedRouteState = FAutoCarNavigationManager.NAVIGATION_STATE_UNAVAILABLE;
        mLastNavigationAppStateJson = buildNavigationStateJson();
        handleNavigationStateChanged(mLastNavigationAppStateJson);
        if (!isEmpty(message)) {
            Log.w(LOG_TAG, message);
        }
    }

    private void handleAppCommandError(String api, int appResult) {
        handleError(buildAppErrorJson(
                api,
                appResult,
                "Navigation app rejected command"));
    }

    private void handleAppRemoteException(String api, RemoteException error) {
        Log.e(LOG_TAG, api + " RemoteException", error);
        clearNavigationAppService();
        handleError(buildErrorJson(
                FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION,
                "Navigation app connection failed",
                api));
    }

    private String buildSearchNearbyResult(
            JSONObject appResult,
            String category,
            int limit,
            int sortedBy) {
        try {
            JSONObject result = new JSONObject();
            result.put("api", "searchNearby");
            result.put("category", category);
            result.put("limit", limit);
            result.put("sortedBy", sortedBy);
            result.put("sortedByName", getSortByName(sortedBy));
            result.put("resultCode", mapAppResultCode(appResult.optInt("resultCode", -7)));
            JSONArray candidates = appResult.optJSONArray("candidates");
            result.put("candidates", candidates != null ? candidates : new JSONArray());
            if (appResult.has("message")) {
                result.put("message", appResult.optString("message"));
            }
            return result.toString();
        } catch (JSONException error) {
            throw new IllegalArgumentException("Unable to build nearby search result", error);
        }
    }

    private int mapCategoryToAppCode(String category) {
        String normalized = category == null ? "" : category.trim().toLowerCase(Locale.US);
        switch (normalized) {
            case FAutoCarNavigationManager.CATEGORY_HOTEL:
                return 1;
            case FAutoCarNavigationManager.CATEGORY_HOSPITAL:
                return 2;
            case FAutoCarNavigationManager.CATEGORY_RESTAURANT:
                return 3;
            case FAutoCarNavigationManager.CATEGORY_GAS_STATION:
                return 4;
            case FAutoCarNavigationManager.CATEGORY_CONVENIENCE_STORE:
                return 5;
            default:
                return 0;
        }
    }

    private int mapSortToAppCode(int sortedBy) {
        return sortedBy == FAutoCarNavigationManager.SORT_BY_RATING ? 2 : 1;
    }

    private int mapAppResultCode(int appResult) {
        if (appResult == 0) {
            return FAutoCarNavigationManager.RESULT_OK;
        }
        if (appResult == -1) {
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }
        if (appResult == -2 || appResult == -4) {
            return FAutoCarNavigationManager.ERROR_UNAVAILABLE;
        }
        return FAutoCarNavigationManager.ERROR_OPERATION_FAILED;
    }

    private int mapAppState(String status) {
        switch (status) {
            case "IDLE":
                return FAutoCarNavigationManager.NAVIGATION_STATE_IDLE;
            case "SHOWING_SUGGESTIONS":
                return FAutoCarNavigationManager.NAVIGATION_STATE_SHOWING_SUGGESTIONS;
            case "ROUTE_CALCULATING":
                return FAutoCarNavigationManager.NAVIGATION_STATE_ROUTE_CALCULATING;
            case "ROUTE_SET":
                return FAutoCarNavigationManager.NAVIGATION_STATE_ROUTE_SET;
            case "SIMULATING_DRIVE":
                return FAutoCarNavigationManager.NAVIGATION_STATE_SIMULATING_DRIVE;
            case "UNAVAILABLE":
            default:
                return FAutoCarNavigationManager.NAVIGATION_STATE_UNAVAILABLE;
        }
    }

    private void notifyCurrentNavigationDataToClient(
            IFautoCarNavigationEventListener listener) {
        if (listener == null) {
            return;
        }

        try {
            listener.onNavigationStateChanged(mLastNavigationAppStateJson != null
                    ? mLastNavigationAppStateJson
                    : buildNavigationStateJson());
            listener.onNavigationDemoModeChanged(mNavigationDemoMode);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "notifyCurrentNavigationDataToClient failed", e);
        }
    }

    private void handleResult(String message) {
        try {
            for (BinderInterfaceContainer.BinderInterface<IFautoCarNavigationEventListener> cb
                    : mFAutoCarNavigation.getInterfaces()) {
                cb.binderInterface.onResult(message);
            }
        } catch (Exception e) {
            Log.i(LOG_TAG, "Handle result exception: " + e);
        }
    }

    private void handleError(String message) {
        try {
            for (BinderInterfaceContainer.BinderInterface<IFautoCarNavigationEventListener> cb
                    : mFAutoCarNavigation.getInterfaces()) {
                cb.binderInterface.onError(message);
            }
        } catch (Exception e) {
            Log.i(LOG_TAG, "Handle error exception: " + e);
        }
    }

    private void handleSearchNearByDataToClient(String data) {
        try {
            for (BinderInterfaceContainer.BinderInterface<IFautoCarNavigationEventListener> cb
                    : mFAutoCarNavigation.getInterfaces()) {
                cb.binderInterface.onSearchNearByCategory(data);
            }
        } catch (Exception e) {
            Log.i(LOG_TAG, "Handle search near by exception: " + e);
        }
    }

    private void handleNavigationStateChanged(String data) {
        try {
            for (BinderInterfaceContainer.BinderInterface<IFautoCarNavigationEventListener> cb
                    : mFAutoCarNavigation.getInterfaces()) {
                cb.binderInterface.onNavigationStateChanged(data);
            }
        } catch (Exception e) {
            Log.i(LOG_TAG, "Handle navigation state exception: " + e);
        }
    }

    private void handleNavigationDemoModeChanged(int mode) {
        try {
            for (BinderInterfaceContainer.BinderInterface<IFautoCarNavigationEventListener> cb
                    : mFAutoCarNavigation.getInterfaces()) {
                cb.binderInterface.onNavigationDemoModeChanged(mode);
            }
        } catch (Exception e) {
            Log.i(LOG_TAG, "Handle navigation demo mode exception: " + e);
        }
    }

    private void handleRouteChanged(String data) {
        try {
            for (BinderInterfaceContainer.BinderInterface<IFautoCarNavigationEventListener> cb
                    : mFAutoCarNavigation.getInterfaces()) {
                cb.binderInterface.onRouteChanged(data);
            }
        } catch (Exception e) {
            Log.i(LOG_TAG, "Handle route changed exception: " + e);
        }
    }

    private String buildNavigationStateJson() {
        return "{"
                + "\"state\":" + mNavigationState + ","
                + "\"stateName\":\"" + getNavigationStateName(mNavigationState) + "\","
                + "\"destination\":\"" + safeJson(mLastDestination) + "\","
                + "\"suggestionId\":\"" + safeJson(mLastSuggestionId) + "\","
                + "\"demoMode\":" + mNavigationDemoMode + ","
                + "\"demoModeName\":\"" + getNavigationDemoModeName(mNavigationDemoMode) + "\","
                + "\"lastSearchCategory\":\"" + safeJson(mLastSearchCategory) + "\","
                + "\"lastSearchLimit\":" + mLastSearchLimit + ","
                + "\"lastSearchSortedBy\":" + mLastSearchSortedBy + ","
                + "\"lastSearchSortedByName\":\"" + getSortByName(mLastSearchSortedBy) + "\""
                + "}";
    }

    private String buildRouteDataJson(String destination, String suggestionId) {
        return "{"
                + "\"api\":\"route\","
                + "\"destination\":\"" + safeJson(destination) + "\","
                + "\"suggestionId\":\"" + safeJson(suggestionId) + "\","
                + "\"state\":" + mNavigationState + ","
                + "\"stateName\":\"" + getNavigationStateName(mNavigationState) + "\","
                + "\"demoMode\":" + mNavigationDemoMode + ","
                + "\"demoModeName\":\"" + getNavigationDemoModeName(mNavigationDemoMode) + "\""
                + "}";
    }

    private String buildErrorJson(int code, String message, String api) {
        return "{"
                + "\"api\":\"" + safeJson(api) + "\","
                + "\"resultCode\":" + code + ","
                + "\"message\":\"" + safeJson(message) + "\""
                + "}";
    }

    private String buildUnavailableStateJson() {
        return "{"
                + "\"state\":" + FAutoCarNavigationManager.NAVIGATION_STATE_UNAVAILABLE + ","
                + "\"stateName\":\"UNAVAILABLE\","
                + "\"destination\":\"\","
                + "\"suggestionId\":\"\","
                + "\"demoMode\":" + mNavigationDemoMode + ","
                + "\"demoModeName\":\"" + getNavigationDemoModeName(mNavigationDemoMode) + "\","
                + "\"lastSearchCategory\":\"" + safeJson(mLastSearchCategory) + "\","
                + "\"lastSearchLimit\":" + mLastSearchLimit + ","
                + "\"lastSearchSortedBy\":" + mLastSearchSortedBy + ","
                + "\"lastSearchSortedByName\":\""
                + getSortByName(mLastSearchSortedBy) + "\""
                + "}";
    }

    private String buildAppErrorJson(String api, int appResultCode, String message) {
        return "{"
                + "\"api\":\"" + safeJson(api) + "\","
                + "\"resultCode\":" + mapAppResultCode(appResultCode) + ","
                + "\"appResultCode\":" + appResultCode + ","
                + "\"message\":\"" + safeJson(message) + "\""
                + "}";
    }

    private String getNavigationStateName(int state) {
        switch (state) {
            case FAutoCarNavigationManager.NAVIGATION_STATE_UNAVAILABLE:
                return "UNAVAILABLE";
            case FAutoCarNavigationManager.NAVIGATION_STATE_IDLE:
                return "IDLE";
            case FAutoCarNavigationManager.NAVIGATION_STATE_SHOWING_SUGGESTIONS:
                return "SHOWING_SUGGESTIONS";
            case FAutoCarNavigationManager.NAVIGATION_STATE_ROUTE_CALCULATING:
                return "ROUTE_CALCULATING";
            case FAutoCarNavigationManager.NAVIGATION_STATE_ROUTE_SET:
                return "ROUTE_SET";
            case FAutoCarNavigationManager.NAVIGATION_STATE_SIMULATING_DRIVE:
                return "SIMULATING_DRIVE";
            default:
                return "UNKNOWN";
        }
    }

    private String getNavigationDemoModeName(int mode) {
        switch (mode) {
            case FAutoCarNavigationManager.NAVIGATION_DEMO_MODE_NORMAL:
                return "NORMAL";
            case FAutoCarNavigationManager.NAVIGATION_DEMO_MODE_TRAFFIC_JAM:
                return "TRAFFIC_JAM";
            case FAutoCarNavigationManager.NAVIGATION_DEMO_MODE_HIGHWAY:
                return "HIGHWAY";
            default:
                return "UNKNOWN";
        }
    }

    private boolean isValidNavigationDemoMode(int mode) {
        return mode == FAutoCarNavigationManager.NAVIGATION_DEMO_MODE_NORMAL
                || mode == FAutoCarNavigationManager.NAVIGATION_DEMO_MODE_TRAFFIC_JAM
                || mode == FAutoCarNavigationManager.NAVIGATION_DEMO_MODE_HIGHWAY;
    }

    private boolean isValidSortBy(int sortedBy) {
        return sortedBy == FAutoCarNavigationManager.SORT_BY_DISTANCE
                || sortedBy == FAutoCarNavigationManager.SORT_BY_RATING;
    }

    private String getSortByName(int sortedBy) {
        switch (sortedBy) {
            case FAutoCarNavigationManager.SORT_BY_DISTANCE:
                return "DISTANCE";
            case FAutoCarNavigationManager.SORT_BY_RATING:
                return "RATING";
            default:
                return "UNKNOWN";
        }
    }

    private boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safeJson(String value) {
        if (value == null) {
            return "";
        }

        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
