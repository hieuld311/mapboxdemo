package com.fauto.car.navigation;

import android.content.ComponentName;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Build;
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

    // ===== DEBUG TEST ONLY: adb broadcast entry point (remove before release) =====
    // Registered only on userdebug/eng builds. This action is intentionally exported
    // so `adb shell am broadcast` can reach this dynamic receiver.
    private static final String ACTION_DEBUG_TEST =
            "com.fauto.car.navigation.action.DEBUG_TEST";
    private static final String EXTRA_DEBUG_COMMAND = "command";
    private static final String EXTRA_DATA = "data";
    private static final String EXTRA_DESTINATION = "destination";
    private static final String EXTRA_CATEGORY = "category";
    private static final String EXTRA_LIMIT = "limit";
    private static final String EXTRA_SORT_BY = "sort_by";
    private static final String EXTRA_SUGGESTION_ID = "suggestion_id";

    private Context mContext;
    private HandlerThread mHandlerThread;
    private Handler mEventHandler;
    // DEBUG TEST ONLY: lifecycle state for the dynamic adb receiver.
    private BroadcastReceiver mDebugReceiver;
    private boolean mDebugReceiverRegistered;

    private final BinderInterfaceContainer<IFautoCarNavigationEventListener> mFAutoCarNavigation =
            new BinderInterfaceContainer<>();

    public static final int MSG_ON_SEND_DATA_TURN_BY_TURN = 0xA000;
    public static final int MSG_ON_GET_SEARCH_NEAR_BY = 0xA001;
    public static final int MSG_ON_SET_ROUTE = 0xA002;
    public static final int MSG_ON_SELECT_SUGGESTION = 0xA003;
    public static final int MSG_ON_SET_NAVIGATION_DEMO_MODE = 0xA004;
    public static final int MSG_ON_START_NAVIGATING_HOME = 0xA005;
    private static final long COMMAND_RESULT_TIMEOUT_MILLIS = 5_000L;

    // API name literals reused across validation/error/result envelopes and command routing.
    private static final String API_SET_ROUTE = "setRoute";
    private static final String API_SELECT_SUGGESTION = "selectSuggestion";
    private static final String API_START_NAVIGATING_HOME = "startNavigatingHome";
    private static final String API_SET_NAVIGATION_DEMO_MODE = "setNavigationDemoMode";
    private static final String API_GET_SEARCH_NEARBY_CATEGORY = "getSearchNearbyCategory";
    private static final String API_SEND_DATA_TURN_BY_TURN = "sendDataTurnByTurn";
    private static final String API_NAVIGATION_FALLBACK = "navigation";

    // Error/status message literals reused across multiple failure paths.
    private static final String MESSAGE_APP_UNAVAILABLE = "Navigation app is unavailable";
    private static final String MESSAGE_COMMAND_FAILED = "Navigation command failed";
    private static final String MESSAGE_SERVICE_RELEASED = "Navigation service is released";
    private static final String MESSAGE_APP_CONNECTION_FAILED = "Navigation app connection failed";

    // JSON field-name literals used when parsing app events / building plugin payloads.
    // Kept separate from the DEBUG-only EXTRA_* intent-extra keys above even though a few
    // share the same text today: those are ADB debug-broadcast keys, these are the plugin's
    // outgoing/incoming wire field names, and the two contracts are free to diverge.
    private static final String JSON_KEY_API = "api";
    private static final String JSON_KEY_STATE = "state";
    private static final String JSON_KEY_STATE_NAME = "stateName";
    private static final String JSON_KEY_DESTINATION = "destination";
    private static final String JSON_KEY_SUGGESTION_ID = "suggestionId";
    private static final String JSON_KEY_RESULT_CODE = "resultCode";
    private static final String JSON_KEY_APP_RESULT_CODE = "appResultCode";
    private static final String JSON_KEY_MESSAGE = "message";
    private static final String JSON_KEY_CATEGORY = "category";
    private static final String JSON_KEY_LIMIT = "limit";
    private static final String JSON_KEY_CANDIDATES = "candidates";

    // The app's "status" vocabulary and the plugin's own stateName output both use this
    // literal for the same concept (mapAppState translates the app's UNAVAILABLE status to
    // NAVIGATION_STATE_UNAVAILABLE, whose display name is this same string), so one shared
    // constant is safe here, unlike the EXTRA_* case above.
    private static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    private int mNavigationState = FAutoCarNavigationManager.NAVIGATION_STATE_IDLE;
    private int mLastDemoMode = FAutoCarNavigationManager.NAVIGATION_DEMO_MODE_NORMAL;

    private String mLastDestination = "";
    private String mLastSuggestionId = "";
    private String mLastSearchCategory = "";
    private int mLastSearchLimit = 0;
    private int mLastSearchSortedBy = FAutoCarNavigationManager.SORT_BY_DISTANCE;
    private final Object mNavigationAppLock = new Object();
    private NaviAidlInterface mNavigationAppService;
    private boolean mNavigationAppBound;
    private volatile boolean mReleased;
    private volatile String mLastNavigationAppStateJson;

    private final INaviListener mNavigationAppListener = new INaviListener.Stub() {
        @Override
        public void onNaviDataReceived(String data) {
            handleNavigationAppData(data);
        }

        @Override
        public void onNavigationStateChanged(String stateJson) {
            publishNavigationAppState(stateJson);
        }

        @Override
        public void onCommandResult(String data) {
            handleAppCommandEvent(data, false);
        }

        @Override
        public void onCommandError(String data) {
            handleAppCommandEvent(data, true);
        }

        @Override
        public void onRouteChanged(String data) {
            publishAppRouteChanged(data);
        }

        @Override
        public void onSearchNearbyResult(String data) {
            forwardSearchNearbyResult(data, mLastSearchCategory, mLastSearchLimit, mLastSearchSortedBy);
        }
    };

    private final ServiceConnection mNavigationAppConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mNavigationAppLock) {
                if (mReleased) {
                    return;
                }
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
            if (mReleased) {
                failReleasedCommand(msg);
                return;
            }
            try {
                switch (msg.what) {
                    case MSG_ON_SEND_DATA_TURN_BY_TURN:
                        sendTurnByTurn((String) msg.obj);
                        break;

                    case MSG_ON_GET_SEARCH_NEAR_BY:
                        handleSearchNearbyCategory((String) msg.obj, msg.arg2, msg.arg1);
                        break;

                    case MSG_ON_SET_ROUTE: {
                        CommandRequest request = (CommandRequest) msg.obj;
                        executeCommand(MSG_ON_SET_ROUTE, request);
                        break;
                    }

                    case MSG_ON_SELECT_SUGGESTION: {
                        CommandRequest request = (CommandRequest) msg.obj;
                        executeCommand(MSG_ON_SELECT_SUGGESTION, request);
                        break;
                    }

                    case MSG_ON_SET_NAVIGATION_DEMO_MODE: {
                        CommandRequest request = (CommandRequest) msg.obj;
                        executeCommand(MSG_ON_SET_NAVIGATION_DEMO_MODE, request);
                        break;
                    }

                    case MSG_ON_START_NAVIGATING_HOME: {
                        CommandRequest request = (CommandRequest) msg.obj;
                        executeCommand(MSG_ON_START_NAVIGATING_HOME, request);
                        break;
                    }

                    default:
                        Log.i(LOG_TAG, "Unexpected message");
                        break;
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "Exception occurred when Handle message", e);
                if (msg.obj instanceof CommandRequest) {
                    CommandRequest request = (CommandRequest) msg.obj;
                    request.fail(FAutoCarNavigationManager.ERROR_OPERATION_FAILED);
                    handleError(buildErrorJson(
                            FAutoCarNavigationManager.ERROR_OPERATION_FAILED,
                            MESSAGE_COMMAND_FAILED,
                            getApiNameForMessage(msg.what)));
                }
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
        if (mReleased) {
            Log.w(LOG_TAG, "init ignored after release");
            return;
        }
        bindNavigationApp();
        // DEBUG TEST ONLY: enables adb commands after the FAuto plugin is initialized.
        registerDebugReceiver();
    }

    @Override
    public void release() {
        Log.d(LOG_TAG, "release");

        mReleased = true;
        // DEBUG TEST ONLY: never leave a context-registered receiver behind.
        unregisterDebugReceiver();
        unbindNavigationApp();

        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            mHandlerThread = null;
            mEventHandler = null;
        }
    }

    // ===== DEBUG TEST ONLY: dynamic receiver and adb command dispatcher =====
    // Keep this block together so it can be removed as one unit after integration testing.
    private void registerDebugReceiver() {
        if (!Build.IS_DEBUGGABLE || mDebugReceiverRegistered || mContext == null
                || mEventHandler == null) {
            return;
        }

        mDebugReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ACTION_DEBUG_TEST.equals(intent.getAction())) {
                    return;
                }
                runDebugCommand(intent);
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_DEBUG_TEST);
        // This platform compiles against an API level before Android 13. The legacy
        // overload is external/shell reachable and is sufficient for this userdebug test.
        mContext.registerReceiver(mDebugReceiver, filter, null, mEventHandler);
        mDebugReceiverRegistered = true;
        Log.i(LOG_TAG, "Debug broadcast receiver registered");
    }

    private void unregisterDebugReceiver() {
        if (!mDebugReceiverRegistered || mContext == null) {
            return;
        }
        try {
            mContext.unregisterReceiver(mDebugReceiver);
        } catch (IllegalArgumentException error) {
            Log.w(LOG_TAG, "Debug broadcast receiver was already unregistered", error);
        }
        mDebugReceiver = null;
        mDebugReceiverRegistered = false;
    }

    private void runDebugCommand(Intent intent) {
        String command = intent.getStringExtra(EXTRA_DEBUG_COMMAND);
        if (command == null) {
            Log.w(LOG_TAG, "DEBUG command is missing");
            return;
        }
        try {
            switch (command) {
                case "turn_by_turn":
                    sendDataTurnByTurn(intent.getStringExtra(EXTRA_DATA));
                    Log.i(LOG_TAG, "DEBUG turn_by_turn dispatched");
                    break;
                case "set_route":
                    Log.i(LOG_TAG, "DEBUG set_route result="
                            + setRoute(intent.getStringExtra(EXTRA_DESTINATION)));
                    break;
                case "nearby":
                    getSearchNearbyCategory(
                            intent.getStringExtra(EXTRA_CATEGORY),
                            intent.getIntExtra(EXTRA_LIMIT, 5),
                            intent.getIntExtra(EXTRA_SORT_BY,
                                    FAutoCarNavigationManager.SORT_BY_DISTANCE));
                    Log.i(LOG_TAG, "DEBUG nearby dispatched");
                    break;
                case "select_suggestion":
                    Log.i(LOG_TAG, "DEBUG select_suggestion result="
                            + selectSuggestion(intent.getStringExtra(EXTRA_SUGGESTION_ID)));
                    break;
                case "home":
                    Log.i(LOG_TAG, "DEBUG home result=" + startNavigatingHome());
                    break;
                case "state":
                    Log.i(LOG_TAG, "DEBUG state=" + getNavigationState());
                    break;
                default:
                    Log.w(LOG_TAG, "DEBUG unknown command=" + command);
                    break;
            }
        } catch (RemoteException error) {
            Log.e(LOG_TAG, "DEBUG command failed: " + command, error);
        }
    }

    // ===== END DEBUG TEST ONLY =====

    private void bindNavigationApp() {
        if (mReleased || mContext == null) {
            return;
        }
        synchronized (mNavigationAppLock) {
            if (mReleased || mNavigationAppBound) {
                return;
            }
        }
        Intent intent = new Intent(NAVIGATION_APP_BIND_ACTION)
                .setComponent(new ComponentName(NAVIGATION_APP_PACKAGE, NAVIGATION_APP_SERVICE));
        try {
            boolean bound = mContext.bindService(intent, mNavigationAppConnection, Context.BIND_AUTO_CREATE);
            boolean unbindAfterRelease = false;
            synchronized (mNavigationAppLock) {
                if (mReleased) {
                    unbindAfterRelease = bound;
                } else {
                    mNavigationAppBound = bound;
                }
            }
            if (unbindAfterRelease) {
                try {
                    mContext.unbindService(mNavigationAppConnection);
                } catch (IllegalArgumentException error) {
                    Log.w(LOG_TAG, "Navigation app was already unbound", error);
                }
                return;
            }
            if (!bound) {
                publishUnavailableState(MESSAGE_APP_UNAVAILABLE);
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

    // Intentional no-op: this service exposes no additional dumpsys diagnostics today.
    // Left as an explicit empty override (rather than omitted) to document that choice and
    // keep the FAutoCarServiceBase contract satisfied; not a placeholder for missed logic.
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
                    API_SEND_DATA_TURN_BY_TURN));
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
                    API_GET_SEARCH_NEARBY_CATEGORY));
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
                    API_SET_ROUTE));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        return dispatchCommand(
                MSG_ON_SET_ROUTE,
                CommandRequest.forString(destination),
                API_SET_ROUTE);
    }

    @Override
    public int selectSuggestion(String suggestionId) throws RemoteException {
        Log.i(LOG_TAG, "selectSuggestion: " + suggestionId);

        if (isEmpty(suggestionId)) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_VALUE_INVALID,
                    "Suggestion id is empty",
                    API_SELECT_SUGGESTION));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        return dispatchCommand(
                MSG_ON_SELECT_SUGGESTION,
                CommandRequest.forString(suggestionId),
                API_SELECT_SUGGESTION);
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

        if (!isValidDemoMode(mode)) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_VALUE_INVALID,
                    "Invalid demo mode: " + mode,
                    API_SET_NAVIGATION_DEMO_MODE));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        return dispatchCommand(
                MSG_ON_SET_NAVIGATION_DEMO_MODE,
                CommandRequest.forInt(mode),
                API_SET_NAVIGATION_DEMO_MODE);
    }

    @Override
    public int startNavigatingHome() throws RemoteException {
        Log.i(LOG_TAG, API_START_NAVIGATING_HOME);

        return dispatchCommand(
                MSG_ON_START_NAVIGATING_HOME,
                CommandRequest.empty(),
                API_START_NAVIGATING_HOME);
    }

    private int dispatchCommand(int messageWhat, CommandRequest request, String api) {
        if (mReleased) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    MESSAGE_SERVICE_RELEASED,
                    api));
            return FAutoCarNavigationManager.ERROR_UNAVAILABLE;
        }
        return BlockingCommandGateway.dispatch(
                mEventHandler,
                messageWhat,
                request,
                COMMAND_RESULT_TIMEOUT_MILLIS,
                FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                FAutoCarNavigationManager.ERROR_OPERATION_FAILED,
                this::executeCommand,
                (resultCode, message) -> handleError(buildErrorJson(resultCode, message, api)));
    }

    private void executeCommand(int messageWhat, CommandRequest request) {
        if (mReleased) {
            request.fail(FAutoCarNavigationManager.ERROR_UNAVAILABLE);
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    MESSAGE_SERVICE_RELEASED,
                    getApiNameForMessage(messageWhat)));
            return;
        }
        if (!request.tryStart()) {
            return;
        }
        try {
            switch (messageWhat) {
                case MSG_ON_SET_ROUTE:
                    request.complete(handleSetRoute(request.stringValue));
                    break;
                case MSG_ON_SELECT_SUGGESTION:
                    request.complete(handleSelectSuggestion(request.stringValue));
                    break;
                case MSG_ON_SET_NAVIGATION_DEMO_MODE:
                    request.complete(handleSetNavigationDemoMode(request.intValue));
                    break;
                case MSG_ON_START_NAVIGATING_HOME:
                    request.complete(handleStartNavigatingHome());
                    break;
                default:
                    request.fail(FAutoCarNavigationManager.ERROR_OPERATION_FAILED);
                    handleError(buildErrorJson(
                            FAutoCarNavigationManager.ERROR_OPERATION_FAILED,
                            "Unknown navigation command",
                            getApiNameForMessage(messageWhat)));
                    break;
            }
        } catch (Exception error) {
            Log.e(LOG_TAG, MESSAGE_COMMAND_FAILED, error);
            request.fail(FAutoCarNavigationManager.ERROR_OPERATION_FAILED);
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_OPERATION_FAILED,
                    MESSAGE_COMMAND_FAILED,
                    getApiNameForMessage(messageWhat)));
        }
    }

    private static String getApiNameForMessage(int messageWhat) {
        switch (messageWhat) {
            case MSG_ON_SET_ROUTE:
                return API_SET_ROUTE;
            case MSG_ON_SELECT_SUGGESTION:
                return API_SELECT_SUGGESTION;
            case MSG_ON_SET_NAVIGATION_DEMO_MODE:
                return API_SET_NAVIGATION_DEMO_MODE;
            case MSG_ON_START_NAVIGATING_HOME:
                return API_START_NAVIGATING_HOME;
            default:
                return API_NAVIGATION_FALLBACK;
        }
    }

    private void failReleasedCommand(Message message) {
        if (!(message.obj instanceof CommandRequest)) {
            return;
        }
        CommandRequest request = (CommandRequest) message.obj;
        request.fail(FAutoCarNavigationManager.ERROR_UNAVAILABLE);
        handleError(buildErrorJson(
                FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                MESSAGE_SERVICE_RELEASED,
                getApiNameForMessage(message.what)));
    }

    private int handleSetRoute(String destination) {
        NaviAidlInterface navigationApp = getNavigationAppService();
        if (navigationApp == null) {
            bindNavigationApp();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    MESSAGE_APP_UNAVAILABLE,
                    API_SET_ROUTE));
            return FAutoCarNavigationManager.ERROR_UNAVAILABLE;
        }
        try {
            int appResult = navigationApp.setRoute(destination);
            if (appResult != 0) {
                handleAppCommandError(API_SET_ROUTE, appResult);
            }
            return mapAppResultCode(appResult);
        } catch (RemoteException error) {
            handleAppRemoteException(API_SET_ROUTE, error);
            return FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION;
        }
    }

    private int handleSelectSuggestion(String suggestionId) {
        NaviAidlInterface navigationApp = getNavigationAppService();
        if (navigationApp == null) {
            bindNavigationApp();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    MESSAGE_APP_UNAVAILABLE,
                    API_SELECT_SUGGESTION));
            return FAutoCarNavigationManager.ERROR_UNAVAILABLE;
        }
        try {
            int appResult = navigationApp.selectSuggestion(suggestionId);
            if (appResult != 0) {
                handleAppCommandError(API_SELECT_SUGGESTION, appResult);
            }
            return mapAppResultCode(appResult);
        } catch (RemoteException error) {
            handleAppRemoteException(API_SELECT_SUGGESTION, error);
            return FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION;
        }
    }

    private int handleSetNavigationDemoMode(int mode) {
        NaviAidlInterface navigationApp = getNavigationAppService();
        if (navigationApp == null) {
            bindNavigationApp();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    MESSAGE_APP_UNAVAILABLE,
                    API_SET_NAVIGATION_DEMO_MODE));
            return FAutoCarNavigationManager.ERROR_UNAVAILABLE;
        }
        try {
            int appResult = navigationApp.setNavigationDemoMode(mode);
            if (appResult != 0) {
                handleAppCommandError(API_SET_NAVIGATION_DEMO_MODE, appResult);
            }
            return mapAppResultCode(appResult);
        } catch (RemoteException error) {
            handleAppRemoteException(API_SET_NAVIGATION_DEMO_MODE, error);
            return FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION;
        }
    }

    private int handleStartNavigatingHome() {
        NaviAidlInterface navigationApp = getNavigationAppService();
        if (navigationApp == null) {
            bindNavigationApp();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    MESSAGE_APP_UNAVAILABLE,
                    API_START_NAVIGATING_HOME));
            return FAutoCarNavigationManager.ERROR_UNAVAILABLE;
        }
        try {
            int appResult = navigationApp.startNavigatingHome();
            if (appResult != 0) {
                handleAppCommandError(API_START_NAVIGATING_HOME, appResult);
            }
            return mapAppResultCode(appResult);
        } catch (RemoteException error) {
            handleAppRemoteException(API_START_NAVIGATING_HOME, error);
            return FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION;
        }
    }

    private int sendTurnByTurn(String data) {
        Log.i(LOG_TAG, "sendTurnByTurn: " + data);

        if (isEmpty(data)) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_VALUE_INVALID,
                    "Turn-by-turn data is empty",
                    API_SEND_DATA_TURN_BY_TURN));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        NaviAidlInterface navigationApp = getNavigationAppService();
        if (navigationApp == null) {
            bindNavigationApp();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    MESSAGE_APP_UNAVAILABLE,
                    API_SEND_DATA_TURN_BY_TURN));
            return FAutoCarNavigationManager.ERROR_UNAVAILABLE;
        }

        try {
            navigationApp.sendNaviData(data);
        } catch (RemoteException error) {
            Log.e(LOG_TAG, "sendNaviData RemoteException", error);
            clearNavigationAppService();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION,
                    MESSAGE_APP_CONNECTION_FAILED,
                    API_SEND_DATA_TURN_BY_TURN));
            return FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION;
        }
        // buildErrorJson is a generic {api,resultCode,message} envelope, reused here for the
        // success ack design item 10 requires.
        handleResult(buildErrorJson(
                FAutoCarNavigationManager.RESULT_OK,
                "Turn-by-turn data delivered",
                API_SEND_DATA_TURN_BY_TURN));
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
                    API_GET_SEARCH_NEARBY_CATEGORY));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        if (limit <= 0) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_VALUE_INVALID,
                    "Invalid limit: " + limit,
                    API_GET_SEARCH_NEARBY_CATEGORY));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        if (!isValidSortBy(sortedBy)) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_VALUE_INVALID,
                    "Invalid sortedBy: " + sortedBy,
                    API_GET_SEARCH_NEARBY_CATEGORY));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        int appCategory = mapCategoryToAppCode(category);
        if (appCategory == 0) {
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_VALUE_INVALID,
                    "Unsupported category: " + category,
                    API_GET_SEARCH_NEARBY_CATEGORY));
            return FAutoCarNavigationManager.ERROR_VALUE_INVALID;
        }

        NaviAidlInterface navigationApp = getNavigationAppService();
        if (navigationApp == null) {
            bindNavigationApp();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_UNAVAILABLE,
                    MESSAGE_APP_UNAVAILABLE,
                    API_GET_SEARCH_NEARBY_CATEGORY));
            return FAutoCarNavigationManager.ERROR_UNAVAILABLE;
        }

        mLastSearchCategory = category;
        mLastSearchLimit = limit;
        mLastSearchSortedBy = sortedBy;
        try {
            // Fire-and-forget: the result arrives later via onSearchNearbyResult.
            navigationApp.searchNearBy(appCategory, limit, mapSortToAppCode(sortedBy));
            return FAutoCarNavigationManager.RESULT_OK;
        } catch (RemoteException error) {
            Log.e(LOG_TAG, "searchNearBy RemoteException", error);
            clearNavigationAppService();
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION,
                    MESSAGE_APP_CONNECTION_FAILED,
                    API_GET_SEARCH_NEARBY_CATEGORY));
            return FAutoCarNavigationManager.ERROR_REMOTE_EXCEPTION;
        }
    }

    private void handleNavigationAppData(String data) {
        try {
            JSONObject event = new JSONObject(data);
            if ("turn-by-turn".equals(event.optString("channel"))) {
                // This stream is consumed by the Launcher through the app-facing AIDL listener.
                // It is not a completion result for a plugin command.
                return;
            }
        } catch (JSONException ignored) {
            // Opaque non-JSON payloads (e.g. echoed sendDataTurnByTurn input) are pass-through.
        }
        handleResult(data);
    }

    /** Shared parsing for the app's onCommandResult/onCommandError push. */
    private void handleAppCommandEvent(String data, boolean isError) {
        try {
            JSONObject event = new JSONObject(data);
            String api = event.optString(JSON_KEY_API, API_NAVIGATION_FALLBACK);
            int appResultCode = event.optInt(JSON_KEY_RESULT_CODE, -7);
            String message = event.optString(
                    JSON_KEY_MESSAGE,
                    isError ? MESSAGE_COMMAND_FAILED : "Navigation command succeeded");
            if (isError) {
                handleError(buildAppErrorJson(api, appResultCode, message));
                return;
            }

            JSONObject result = new JSONObject();
            result.put(JSON_KEY_API, api);
            result.put(JSON_KEY_RESULT_CODE, mapAppResultCode(appResultCode));
            result.put(JSON_KEY_APP_RESULT_CODE, appResultCode);
            result.put(JSON_KEY_MESSAGE, message);
            JSONObject destination = event.optJSONObject(JSON_KEY_DESTINATION);
            if (destination != null) {
                result.put(JSON_KEY_DESTINATION, destination);
            }
            handleResult(result.toString());
        } catch (JSONException error) {
            Log.e(LOG_TAG, "Invalid navigation command event", error);
            if (isError) {
                handleError(buildErrorJson(
                        FAutoCarNavigationManager.ERROR_OPERATION_FAILED,
                        "Invalid navigation command event",
                        API_NAVIGATION_FALLBACK));
            } else {
                handleResult(data);
            }
        }
    }

    /** The app already owns the ROUTE_SET transition; just relay its destination. */
    private void publishAppRouteChanged(String data) {
        try {
            JSONObject destination = new JSONObject(data);
            String name = destination.optString("name", mLastDestination);
            String suggestionId = destination.optString(JSON_KEY_SUGGESTION_ID, mLastSuggestionId);
            handleRouteChanged(buildRouteDataJson(name, suggestionId));
        } catch (JSONException error) {
            Log.e(LOG_TAG, "Invalid route changed payload", error);
        }
    }

    private void forwardSearchNearbyResult(
            String appResultJson,
            String category,
            int limit,
            int sortedBy) {
        try {
            JSONObject appResult = new JSONObject(appResultJson);
            int appResultCode = appResult.optInt(JSON_KEY_RESULT_CODE, -7);
            if (appResultCode != 0) {
                handleError(buildAppErrorJson(
                        API_GET_SEARCH_NEARBY_CATEGORY,
                        appResultCode,
                        appResult.optString(JSON_KEY_MESSAGE, "Nearby search failed")));
                return;
            }
            handleSearchNearByDataToClient(
                    buildSearchNearbyResult(appResult, category, limit, sortedBy));
        } catch (Exception error) {
            Log.e(LOG_TAG, "Invalid search result from navigation app", error);
            handleError(buildErrorJson(
                    FAutoCarNavigationManager.ERROR_OPERATION_FAILED,
                    "Navigation app returned an invalid search result",
                    API_GET_SEARCH_NEARBY_CATEGORY));
        }
    }

    private void publishNavigationAppState(String appStateJson) {
        if (isEmpty(appStateJson)) {
            publishUnavailableState("Navigation app returned an empty state");
            return;
        }
        try {
            JSONObject appState = new JSONObject(appStateJson);
            String status = appState.optString("status", STATUS_UNAVAILABLE);
            int state = mapAppState(status);
            JSONObject destination = appState.optJSONObject(JSON_KEY_DESTINATION);
            int demoMode = appState.optInt(
                    "demoMode", FAutoCarNavigationManager.NAVIGATION_DEMO_MODE_NORMAL);
            if (isValidDemoMode(demoMode) && demoMode != mLastDemoMode) {
                mLastDemoMode = demoMode;
                handleNavigationDemoModeChanged(demoMode);
            }

            mNavigationState = state;
            if (destination != null) {
                mLastDestination = destination.optString("name", "");
                mLastSuggestionId = destination.optString(JSON_KEY_SUGGESTION_ID, "");
            } else {
                mLastDestination = "";
                mLastSuggestionId = "";
            }
            JSONObject pluginState = new JSONObject();
            pluginState.put(JSON_KEY_STATE, state);
            pluginState.put(JSON_KEY_STATE_NAME, getNavigationStateName(state));
            pluginState.put(JSON_KEY_DESTINATION, mLastDestination);
            pluginState.put(JSON_KEY_SUGGESTION_ID, mLastSuggestionId);
            pluginState.put("lastSearchCategory", mLastSearchCategory);
            pluginState.put("lastSearchLimit", mLastSearchLimit);
            pluginState.put("lastSearchSortedBy", mLastSearchSortedBy);
            pluginState.put("lastSearchSortedByName", getSortByName(mLastSearchSortedBy));
            pluginState.put("appState", appState);
            mLastNavigationAppStateJson = pluginState.toString();
            handleNavigationStateChanged(mLastNavigationAppStateJson);
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
            int state = mapAppState(appState.optString("status", STATUS_UNAVAILABLE));
            JSONObject destination = appState.optJSONObject(JSON_KEY_DESTINATION);
            JSONObject pluginState = new JSONObject();
            pluginState.put(JSON_KEY_STATE, state);
            pluginState.put(JSON_KEY_STATE_NAME, getNavigationStateName(state));
            pluginState.put(JSON_KEY_DESTINATION, destination != null
                    ? destination.optString("name", "") : "");
            pluginState.put(JSON_KEY_SUGGESTION_ID, destination != null
                    ? destination.optString(JSON_KEY_SUGGESTION_ID, "") : "");
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
                MESSAGE_APP_CONNECTION_FAILED,
                api));
    }

    private String buildSearchNearbyResult(
            JSONObject appResult,
            String category,
            int limit,
            int sortedBy) {
        try {
            JSONObject result = new JSONObject();
            result.put(JSON_KEY_API, "searchNearby");
            result.put(JSON_KEY_CATEGORY, category);
            result.put(JSON_KEY_LIMIT, limit);
            result.put("sortedBy", sortedBy);
            result.put("sortedByName", getSortByName(sortedBy));
            result.put(JSON_KEY_RESULT_CODE, mapAppResultCode(appResult.optInt(JSON_KEY_RESULT_CODE, -7)));
            JSONArray candidates = appResult.optJSONArray(JSON_KEY_CANDIDATES);
            result.put(JSON_KEY_CANDIDATES, candidates != null ? candidates : new JSONArray());
            if (appResult.has(JSON_KEY_MESSAGE)) {
                result.put(JSON_KEY_MESSAGE, appResult.optString(JSON_KEY_MESSAGE));
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
            case STATUS_UNAVAILABLE:
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
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "notifyCurrentNavigationDataToClient failed", e);
        }
    }

    /**
     * The board's legacy FAuto base library exposes getInterfaces() as a raw Iterable.
     * Keep the unchecked boundary here so callback code remains strongly typed.
     */
    @SuppressWarnings("unchecked")
    private Iterable<BinderInterfaceContainer.BinderInterface<IFautoCarNavigationEventListener>>
            getNavigationCallbacks() {
        return (Iterable<BinderInterfaceContainer.BinderInterface<IFautoCarNavigationEventListener>>)
                (Iterable<?>) mFAutoCarNavigation.getInterfaces();
    }

    private void handleResult(String message) {
        try {
            for (BinderInterfaceContainer.BinderInterface<IFautoCarNavigationEventListener> cb
                    : getNavigationCallbacks()) {
                cb.binderInterface.onResult(message);
            }
        } catch (Exception e) {
            Log.i(LOG_TAG, "Handle result exception: " + e);
        }
    }

    private void handleError(String message) {
        try {
            for (BinderInterfaceContainer.BinderInterface<IFautoCarNavigationEventListener> cb
                    : getNavigationCallbacks()) {
                cb.binderInterface.onError(message);
            }
        } catch (Exception e) {
            Log.i(LOG_TAG, "Handle error exception: " + e);
        }
    }

    private void handleSearchNearByDataToClient(String data) {
        try {
            for (BinderInterfaceContainer.BinderInterface<IFautoCarNavigationEventListener> cb
                    : getNavigationCallbacks()) {
                cb.binderInterface.onSearchNearByCategory(data);
            }
        } catch (Exception e) {
            Log.i(LOG_TAG, "Handle search near by exception: " + e);
        }
    }

    private void handleNavigationStateChanged(String data) {
        try {
            for (BinderInterfaceContainer.BinderInterface<IFautoCarNavigationEventListener> cb
                    : getNavigationCallbacks()) {
                cb.binderInterface.onNavigationStateChanged(data);
            }
        } catch (Exception e) {
            Log.i(LOG_TAG, "Handle navigation state exception: " + e);
        }
    }

    private void handleNavigationDemoModeChanged(int mode) {
        try {
            for (BinderInterfaceContainer.BinderInterface<IFautoCarNavigationEventListener> cb
                    : getNavigationCallbacks()) {
                cb.binderInterface.onNavigationDemoModeChanged(mode);
            }
        } catch (Exception e) {
            Log.i(LOG_TAG, "Handle navigation demo mode changed exception: " + e);
        }
    }

    private void handleRouteChanged(String data) {
        try {
            for (BinderInterfaceContainer.BinderInterface<IFautoCarNavigationEventListener> cb
                    : getNavigationCallbacks()) {
                cb.binderInterface.onRouteChanged(data);
            }
        } catch (Exception e) {
            Log.i(LOG_TAG, "Handle route changed exception: " + e);
        }
    }

    private String buildNavigationStateJson() {
        return PluginJson.navigationState(
                mNavigationState,
                getNavigationStateName(mNavigationState),
                mLastDestination,
                mLastSuggestionId,
                mLastSearchCategory,
                mLastSearchLimit,
                mLastSearchSortedBy,
                getSortByName(mLastSearchSortedBy));
    }

    private String buildRouteDataJson(String destination, String suggestionId) {
        return PluginJson.routeData(
                destination, suggestionId, mNavigationState, getNavigationStateName(mNavigationState));
    }

    private String buildErrorJson(int code, String message, String api) {
        return PluginJson.errorJson(code, message, api);
    }

    private String buildUnavailableStateJson() {
        return PluginJson.unavailableState(
                FAutoCarNavigationManager.NAVIGATION_STATE_UNAVAILABLE,
                mLastSearchCategory,
                mLastSearchLimit,
                mLastSearchSortedBy,
                getSortByName(mLastSearchSortedBy));
    }

    private String buildAppErrorJson(String api, int appResultCode, String message) {
        return PluginJson.appErrorJson(api, appResultCode, mapAppResultCode(appResultCode), message);
    }

    private String getNavigationStateName(int state) {
        switch (state) {
            case FAutoCarNavigationManager.NAVIGATION_STATE_UNAVAILABLE:
                return STATUS_UNAVAILABLE;
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

    private boolean isValidDemoMode(int mode) {
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

}
