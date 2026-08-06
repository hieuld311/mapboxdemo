package fauto.car.navigation;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.HashSet;

import fauto.car.base.FAutoCarManagerBase;

public class FAutoCarNavigationManager implements FAutoCarManagerBase {
    private static final String LOG_TAG = "FAutoCarNavigationManager";

    public static final String NAVIGATION_SERVICE =
            "com.fauto.car.navigation.FAutoCarNavigationService";

    public static final String PERMISSION_NAVIGATION =
            "fauto.car.permission.NAVIGATION";

    public static final String PERMISSION_CONTROL_NAVIGATION =
            "fauto.car.permission.CONTROL_NAVIGATION";

    public static final int RESULT_OK = 0;
    public static final int ERROR_UNAVAILABLE = -1;
    public static final int ERROR_VALUE_INVALID = -2;
    public static final int ERROR_REMOTE_EXCEPTION = -3;
    public static final int ERROR_OPERATION_FAILED = -4;

    public static final int NAVIGATION_STATE_UNAVAILABLE = -1;
    public static final int NAVIGATION_STATE_IDLE = 0;
    public static final int NAVIGATION_STATE_SHOWING_SUGGESTIONS = 1;
    public static final int NAVIGATION_STATE_ROUTE_CALCULATING = 2;
    public static final int NAVIGATION_STATE_ROUTE_SET = 3;
    public static final int NAVIGATION_STATE_SIMULATING_DRIVE = 4;

    public static final int NAVIGATION_DEMO_MODE_NORMAL = 0;
    public static final int NAVIGATION_DEMO_MODE_TRAFFIC_JAM = 1;
    public static final int NAVIGATION_DEMO_MODE_HIGHWAY = 2;

    public static final String CATEGORY_HOTEL = "hotel";
    public static final String CATEGORY_HOSPITAL = "hospital";
    public static final String CATEGORY_RESTAURANT = "restaurant";
    public static final String CATEGORY_GAS_STATION = "gas_station";
    public static final String CATEGORY_CONVENIENCE_STORE = "convenience_store";

    public static final int SORT_BY_DISTANCE = 0;
    public static final int SORT_BY_RATING = 1;

    @IntDef({
            RESULT_OK,
            ERROR_UNAVAILABLE,
            ERROR_VALUE_INVALID,
            ERROR_REMOTE_EXCEPTION,
            ERROR_OPERATION_FAILED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NavigationResultCode {
    }

    @IntDef({
            NAVIGATION_STATE_UNAVAILABLE,
            NAVIGATION_STATE_IDLE,
            NAVIGATION_STATE_SHOWING_SUGGESTIONS,
            NAVIGATION_STATE_ROUTE_CALCULATING,
            NAVIGATION_STATE_ROUTE_SET,
            NAVIGATION_STATE_SIMULATING_DRIVE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NavigationState {
    }

    @IntDef({
            NAVIGATION_DEMO_MODE_NORMAL,
            NAVIGATION_DEMO_MODE_TRAFFIC_JAM,
            NAVIGATION_DEMO_MODE_HIGHWAY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NavigationDemoMode {
    }

    @IntDef({
            SORT_BY_DISTANCE,
            SORT_BY_RATING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NavigationSortBy {
    }

    private final Handler mHandler;
    private final IFautoCarNavigationEventListener mNavigationEventListenerToService;
    private final HashSet<FAutoCarNavigationEventListener> mNavigationEventListeners =
            new HashSet<>();

    private IFAutoCarNavigation mService;

    public FAutoCarNavigationManager(Context context, Handler handler) {
        Log.d(LOG_TAG, "Constructor");
        mHandler = handler != null ? handler : new Handler(Looper.getMainLooper());
        mNavigationEventListenerToService = new NavigationEventListenerToService(this);
    }

    @Override
    public void registerService(IBinder service) {
        Log.d(LOG_TAG, "registerService");
        mService = IFAutoCarNavigation.Stub.asInterface(service);
        registerCallbackToServiceIfNeeded();
    }

    @Override
    public void unregisterService() {
        Log.d(LOG_TAG, "unregisterService");
        unregisterCallbackFromService();
        mService = null;
    }

    @RequiresPermission(PERMISSION_NAVIGATION)
    public void registerFAutoCarNavigationEventListener(
            @NonNull FAutoCarNavigationEventListener listener) {
        if (listener == null) {
            Log.w(LOG_TAG, "register listener ignored: listener is null");
            return;
        }

        synchronized (mNavigationEventListeners) {
            boolean shouldRegisterToService = mNavigationEventListeners.isEmpty();
            mNavigationEventListeners.add(listener);

            if (shouldRegisterToService) {
                registerCallbackToService();
            }
        }
    }

    @RequiresPermission(PERMISSION_NAVIGATION)
    public void unregisterFAutoCarNavigationEventListener(
            @NonNull FAutoCarNavigationEventListener listener) {
        if (listener == null) {
            Log.w(LOG_TAG, "unregister listener ignored: listener is null");
            return;
        }

        synchronized (mNavigationEventListeners) {
            mNavigationEventListeners.remove(listener);

            if (mNavigationEventListeners.isEmpty()) {
                unregisterCallbackFromService();
            }
        }
    }

    @RequiresPermission(PERMISSION_CONTROL_NAVIGATION)
    public void sendDataTurnByTurn(@NonNull String data) {
        if (mService == null) {
            Log.w(LOG_TAG, "sendDataTurnByTurn ignored: service is null");
            dispatchLocalError(ERROR_UNAVAILABLE, "sendDataTurnByTurn", "Navigation service is unavailable");
            return;
        }

        if (data == null || data.trim().isEmpty()) {
            dispatchLocalError(ERROR_VALUE_INVALID, "sendDataTurnByTurn", "Turn-by-turn data is empty");
            return;
        }

        try {
            mService.sendDataTurnByTurn(data);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "sendDataTurnByTurn RemoteException", e);
            dispatchLocalError(ERROR_REMOTE_EXCEPTION, "sendDataTurnByTurn", "Navigation service connection failed");
        }
    }

    @RequiresPermission(PERMISSION_CONTROL_NAVIGATION)
    public void getSearchNearbyCategory(
            @NonNull String category,
            int limit,
            @NavigationSortBy int sortedBy
    ) {
        if (mService == null) {
            Log.w(LOG_TAG, "getSearchNearbyCategory ignored: service is null");
            dispatchLocalError(ERROR_UNAVAILABLE, "getSearchNearbyCategory", "Navigation service is unavailable");
            return;
        }

        if (category == null || category.trim().isEmpty()) {
            Log.w(LOG_TAG, "getSearchNearbyCategory ignored: category is empty");
            dispatchLocalError(ERROR_VALUE_INVALID, "getSearchNearbyCategory", "Category is empty");
            return;
        }

        if (limit <= 0) {
            Log.w(LOG_TAG, "getSearchNearbyCategory ignored: invalid limit=" + limit);
            dispatchLocalError(ERROR_VALUE_INVALID, "getSearchNearbyCategory", "Limit must be greater than zero");
            return;
        }

        if (!isValidSortBy(sortedBy)) {
            Log.w(LOG_TAG, "getSearchNearbyCategory ignored: invalid sortedBy=" + sortedBy);
            dispatchLocalError(ERROR_VALUE_INVALID, "getSearchNearbyCategory", "Invalid sort mode");
            return;
        }

        try {
            mService.getSearchNearbyCategory(category, limit, sortedBy);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "getSearchNearbyCategory RemoteException", e);
            dispatchLocalError(ERROR_REMOTE_EXCEPTION, "getSearchNearbyCategory", "Navigation service connection failed");
        }
    }

    @RequiresPermission(PERMISSION_CONTROL_NAVIGATION)
    public @NavigationResultCode int setRoute(@NonNull String destination) {
        if (mService == null) {
            dispatchLocalError(ERROR_UNAVAILABLE, "setRoute", "Navigation service is unavailable");
            return ERROR_UNAVAILABLE;
        }

        if (destination == null || destination.trim().isEmpty()) {
            dispatchLocalError(ERROR_VALUE_INVALID, "setRoute", "Destination is empty");
            return ERROR_VALUE_INVALID;
        }

        try {
            return mService.setRoute(destination);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "setRoute RemoteException", e);
            dispatchLocalError(ERROR_REMOTE_EXCEPTION, "setRoute", "Navigation service connection failed");
            return ERROR_REMOTE_EXCEPTION;
        }
    }

    @RequiresPermission(PERMISSION_CONTROL_NAVIGATION)
    public @NavigationResultCode int selectSuggestion(@NonNull String suggestionId) {
        if (mService == null) {
            dispatchLocalError(ERROR_UNAVAILABLE, "selectSuggestion", "Navigation service is unavailable");
            return ERROR_UNAVAILABLE;
        }

        if (suggestionId == null || suggestionId.trim().isEmpty()) {
            dispatchLocalError(ERROR_VALUE_INVALID, "selectSuggestion", "Suggestion id is empty");
            return ERROR_VALUE_INVALID;
        }

        try {
            return mService.selectSuggestion(suggestionId);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "selectSuggestion RemoteException", e);
            dispatchLocalError(
                    ERROR_REMOTE_EXCEPTION,
                    "selectSuggestion",
                    "Navigation service connection failed");
            return ERROR_REMOTE_EXCEPTION;
        }
    }

    @RequiresPermission(PERMISSION_NAVIGATION)
    public String getNavigationState() {
        if (mService == null) {
            return createUnavailableStateJson();
        }

        try {
            return mService.getNavigationState();
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "getNavigationState RemoteException", e);
            return createUnavailableStateJson();
        }
    }

    @RequiresPermission(PERMISSION_CONTROL_NAVIGATION)
    public @NavigationResultCode int setNavigationDemoMode(@NavigationDemoMode int mode) {
        if (mService == null) {
            dispatchLocalError(
                    ERROR_UNAVAILABLE,
                    "setNavigationDemoMode",
                    "Navigation service is unavailable");
            return ERROR_UNAVAILABLE;
        }

        if (!isValidNavigationDemoMode(mode)) {
            dispatchLocalError(ERROR_VALUE_INVALID, "setNavigationDemoMode", "Invalid navigation demo mode");
            return ERROR_VALUE_INVALID;
        }

        try {
            return mService.setNavigationDemoMode(mode);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "setNavigationDemoMode RemoteException", e);
            dispatchLocalError(
                    ERROR_REMOTE_EXCEPTION,
                    "setNavigationDemoMode",
                    "Navigation service connection failed");
            return ERROR_REMOTE_EXCEPTION;
        }
    }

    @RequiresPermission(PERMISSION_CONTROL_NAVIGATION)
    public @NavigationResultCode int startNavigatingHome() {
        if (mService == null) {
            dispatchLocalError(
                    ERROR_UNAVAILABLE,
                    "startNavigatingHome",
                    "Navigation service is unavailable");
            return ERROR_UNAVAILABLE;
        }

        try {
            return mService.startNavigatingHome();
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "startNavigatingHome RemoteException", e);
            dispatchLocalError(
                    ERROR_REMOTE_EXCEPTION,
                    "startNavigatingHome",
                    "Navigation service connection failed");
            return ERROR_REMOTE_EXCEPTION;
        }
    }

    private void registerCallbackToServiceIfNeeded() {
        synchronized (mNavigationEventListeners) {
            if (!mNavigationEventListeners.isEmpty()) {
                registerCallbackToService();
            }
        }
    }

    private void registerCallbackToService() {
        if (mService == null) {
            Log.w(LOG_TAG, "registerCallbackToService ignored: service is null");
            return;
        }

        try {
            mService.registerFAutoCarNavigationEventListener(mNavigationEventListenerToService);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "registerCallbackToService RemoteException", e);
        }
    }

    private void unregisterCallbackFromService() {
        if (mService == null) {
            return;
        }

        try {
            mService.unregisterFAutoCarNavigationEventListener(mNavigationEventListenerToService);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "unregisterCallbackFromService RemoteException", e);
        }
    }

    private static boolean isValidNavigationDemoMode(int mode) {
        return mode == NAVIGATION_DEMO_MODE_NORMAL
                || mode == NAVIGATION_DEMO_MODE_TRAFFIC_JAM
                || mode == NAVIGATION_DEMO_MODE_HIGHWAY;
    }

    private static boolean isValidSortBy(int sortedBy) {
        return sortedBy == SORT_BY_DISTANCE || sortedBy == SORT_BY_RATING;
    }

    private static String createUnavailableStateJson() {
        return "{"
                + "\"state\":" + NAVIGATION_STATE_UNAVAILABLE + ","
                + "\"stateName\":\"UNAVAILABLE\","
                + "\"destination\":\"\","
                + "\"suggestionId\":\"\","
                + "\"demoMode\":" + NAVIGATION_DEMO_MODE_NORMAL + ","
                + "\"demoModeName\":\"NORMAL\""
                + "}";
    }

    private static String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private HashSet<FAutoCarNavigationEventListener> snapshotListeners() {
        synchronized (mNavigationEventListeners) {
            return new HashSet<>(mNavigationEventListeners);
        }
    }

    private void dispatchResult(String data) {
        for (FAutoCarNavigationEventListener listener : snapshotListeners()) {
            mHandler.post(() -> listener.onResult(data));
        }
    }

    private void dispatchError(String data) {
        for (FAutoCarNavigationEventListener listener : snapshotListeners()) {
            mHandler.post(() -> listener.onError(data));
        }
    }

    private void dispatchLocalError(int resultCode, String api, String message) {
        dispatchError("{"
                + "\"api\":\"" + escapeJson(api) + "\","
                + "\"resultCode\":" + resultCode + ","
                + "\"message\":\"" + escapeJson(message) + "\""
                + "}");
    }

    private void dispatchSearchNearByCategory(String data) {
        for (FAutoCarNavigationEventListener listener : snapshotListeners()) {
            mHandler.post(() -> listener.onSearchNearByCategory(data));
        }
    }

    private void dispatchNavigationStateChanged(String data) {
        for (FAutoCarNavigationEventListener listener : snapshotListeners()) {
            mHandler.post(() -> listener.onNavigationStateChanged(data));
        }
    }

    private void dispatchNavigationDemoModeChanged(int mode) {
        for (FAutoCarNavigationEventListener listener : snapshotListeners()) {
            mHandler.post(() -> listener.onNavigationDemoModeChanged(mode));
        }
    }

    private void dispatchRouteChanged(String data) {
        for (FAutoCarNavigationEventListener listener : snapshotListeners()) {
            mHandler.post(() -> listener.onRouteChanged(data));
        }
    }

    private static final class NavigationEventListenerToService
            extends IFautoCarNavigationEventListener.Stub {
        private final WeakReference<FAutoCarNavigationManager> mManagerRef;

        NavigationEventListenerToService(FAutoCarNavigationManager manager) {
            mManagerRef = new WeakReference<>(manager);
        }

        @Override
        public void onResult(String data) {
            FAutoCarNavigationManager manager = mManagerRef.get();
            if (manager != null) {
                manager.dispatchResult(data);
            }
        }

        @Override
        public void onError(String data) {
            FAutoCarNavigationManager manager = mManagerRef.get();
            if (manager != null) {
                manager.dispatchError(data);
            }
        }

        @Override
        public void onSearchNearByCategory(String data) {
            FAutoCarNavigationManager manager = mManagerRef.get();
            if (manager != null) {
                manager.dispatchSearchNearByCategory(data);
            }
        }

        @Override
        public void onNavigationStateChanged(String data) {
            FAutoCarNavigationManager manager = mManagerRef.get();
            if (manager != null) {
                manager.dispatchNavigationStateChanged(data);
            }
        }

        @Override
        public void onNavigationDemoModeChanged(int mode) {
            FAutoCarNavigationManager manager = mManagerRef.get();
            if (manager != null) {
                manager.dispatchNavigationDemoModeChanged(mode);
            }
        }

        @Override
        public void onRouteChanged(String data) {
            FAutoCarNavigationManager manager = mManagerRef.get();
            if (manager != null) {
                manager.dispatchRouteChanged(data);
            }
        }
    }

    public interface FAutoCarNavigationEventListener {
        void onResult(String data);

        void onError(String data);

        void onSearchNearByCategory(String data);

        void onNavigationStateChanged(String data);

        void onNavigationDemoModeChanged(@NavigationDemoMode int mode);

        void onRouteChanged(String data);
    }
}
