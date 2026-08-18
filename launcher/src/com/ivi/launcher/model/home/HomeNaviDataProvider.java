package com.ivi.launcher.model.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.ivi.car.navigation.INaviListener;
import com.ivi.car.navigation.NaviAidlInterface;
import com.ivi.launcher.model.Navigation;

public class HomeNaviDataProvider {
    private static final String TAG = "HomeNaviDataProvider";
    private static final String NAVI_SERVICE_ACTION = "com.ivi.car.navigation.service.NaviAIDLService";
    private static final String NAVI_PACKAGE = "com.ivi.car.navigation";

    public interface Listener {
        void onNaviDataChanged(@NonNull NaviInfo info);
    }

    public static final class NaviInfo {
        public final boolean active;
        public final String turnType;
        public final String stepDistance;
        public final String stepUnit;
        public final String stepRoad;
        public final String destination;
        public final String remainingDistance;
        public final String remainingUnit;
        public final int etaMinutes;
        // Overall route fraction traveled, scaled 0-1000 to match ProgressBar's max (see
        // percentTraveledToProgress below) - drives the compact card's live progress bar.
        public final int percentTraveled;

        public NaviInfo(boolean active, String turnType, String stepDistance,
                String stepUnit, String stepRoad, String destination,
                String remainingDistance, String remainingUnit, int etaMinutes,
                int percentTraveled) {
            this.active = active;
            this.turnType = turnType != null ? turnType : "";
            this.stepDistance = stepDistance != null ? stepDistance : "";
            this.stepUnit = stepUnit != null ? stepUnit : "";
            this.stepRoad = stepRoad != null ? stepRoad : "";
            this.destination = destination != null ? destination : "";
            this.remainingDistance = remainingDistance != null ? remainingDistance : "";
            this.remainingUnit = remainingUnit != null ? remainingUnit : "";
            this.etaMinutes = etaMinutes;
            this.percentTraveled = percentTraveled;
        }

        public static NaviInfo idle() {
            return new NaviInfo(false, "", "", "", "", "", "", "", 0, 0);
        }
    }

    private final Context mContext;
    private final Handler mMainHandler;
    private final Listener mListener;
    private final Gson mGson = new Gson();

    @Nullable private NaviAidlInterface mNaviAidl;
    private boolean mBound = false;

    private final INaviListener.Stub mNaviListener = new INaviListener.Stub() {
        @Override
        public void onNaviDataReceived(String jsonString) {
            Log.d(TAG, "onNaviDataReceived: " + jsonString);
            try {
                Navigation nav = mGson.fromJson(jsonString, Navigation.class);
                if (nav == null) {
                    mMainHandler.post(() -> mListener.onNaviDataChanged(NaviInfo.idle()));
                    return;
                }
                NaviInfo info = new NaviInfo(
                        true,
                        nav.getType(),
                        formatDistance(nav.getStepDistance()),
                        nav.getStepUnit(),
                        nav.getStepRoad(),
                        nav.getDestination(),
                        formatDistance(nav.getDistance()),
                        nav.getDistanceUnit(),
                        durationToMinutes(nav.getDuration()),
                        percentTraveledToProgress(nav.getPercentTraveled())
                );
                mMainHandler.post(() -> mListener.onNaviDataChanged(info));
            } catch (JsonSyntaxException e) {
                Log.e(TAG, "onNaviDataReceived: JSON parse error", e);
            }
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected");
            mNaviAidl = NaviAidlInterface.Stub.asInterface(service);
            try {
                mNaviAidl.registerListener(mNaviListener);
            } catch (RemoteException e) {
                Log.e(TAG, "registerListener failed", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected");
            mNaviAidl = null;
            mMainHandler.post(() -> mListener.onNaviDataChanged(NaviInfo.idle()));
        }
    };

    public HomeNaviDataProvider(
            @NonNull Context context,
            @NonNull Handler mainHandler,
            @NonNull Listener listener
    ) {
        mContext = context.getApplicationContext();
        mMainHandler = mainHandler;
        mListener = listener;
    }

    public void start() {
        Log.d(TAG, "start: binding NaviAIDLService");
        Intent intent = new Intent(NAVI_SERVICE_ACTION);
        intent.setPackage(NAVI_PACKAGE);
        try {
            mBound = mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
            if (!mBound) {
                Log.w(TAG, "start: bindService returned false — navigation app may not be installed");
            }
        } catch (Exception e) {
            Log.e(TAG, "start: bindService exception", e);
        }
    }

    public void stop() {
        Log.d(TAG, "stop");
        if (mBound) {
            if (mNaviAidl != null) {
                try {
                    mNaviAidl.unregisterListener(mNaviListener);
                } catch (RemoteException e) {
                    Log.e(TAG, "stop: unregisterListener failed", e);
                }
                mNaviAidl = null;
            }
            try {
                mContext.unbindService(mConnection);
            } catch (Exception e) {
                Log.e(TAG, "stop: unbindService exception", e);
            }
            mBound = false;
        }
    }

    private static String formatDistance(double distance) {
        String str = Double.toString(distance);
        if (str.endsWith(".0")) {
            str = str.substring(0, str.length() - 2);
        }
        return str;
    }

    private static int durationToMinutes(int durationSeconds) {
        if (durationSeconds <= 0) return 0;
        int minutes = durationSeconds / 60;
        return minutes < 1 ? 1 : minutes;
    }

    /** Clamps a 0.0-1.0 fraction and scales it to 0-1000, matching ProgressBar's max. */
    private static int percentTraveledToProgress(double fraction) {
        double clamped = Math.max(0.0, Math.min(1.0, fraction));
        return (int) Math.round(clamped * 1000);
    }
}
