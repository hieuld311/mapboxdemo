package com.ivi.launcher.model.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.ivi.car.navigation.INaviListener;
import com.ivi.car.navigation.NaviAidlInterface;
import com.ivi.launcher.model.Navigation;

import org.json.JSONException;
import org.json.JSONObject;

public class HomeNaviDataProvider {
    private static final String TAG = "HomeNaviDataProvider";
    private static final String NAVI_SERVICE_ACTION = "com.ivi.car.navigation.service.NaviAIDLService";
    private static final String NAVI_PACKAGE = "com.ivi.car.navigation";
    private static final String CHANNEL_MAP_SNAPSHOT = "map-snapshot";

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
        // True only for a "map-snapshot" channel update - see HomeCardDataRepository, which
        // uses this to touch only HomeCardItem.naviMapSnapshot and leave the TBT text fields
        // (all of the above) untouched, since a snapshot tick carries none of them.
        public final boolean isSnapshotUpdate;
        @Nullable public final Bitmap mapSnapshot;

        public NaviInfo(boolean active, String turnType, String stepDistance,
                String stepUnit, String stepRoad, String destination,
                String remainingDistance, String remainingUnit, int etaMinutes) {
            this(active, turnType, stepDistance, stepUnit, stepRoad, destination,
                    remainingDistance, remainingUnit, etaMinutes, false, null);
        }

        private NaviInfo(boolean active, String turnType, String stepDistance,
                String stepUnit, String stepRoad, String destination,
                String remainingDistance, String remainingUnit, int etaMinutes,
                boolean isSnapshotUpdate, @Nullable Bitmap mapSnapshot) {
            this.active = active;
            this.turnType = turnType != null ? turnType : "";
            this.stepDistance = stepDistance != null ? stepDistance : "";
            this.stepUnit = stepUnit != null ? stepUnit : "";
            this.stepRoad = stepRoad != null ? stepRoad : "";
            this.destination = destination != null ? destination : "";
            this.remainingDistance = remainingDistance != null ? remainingDistance : "";
            this.remainingUnit = remainingUnit != null ? remainingUnit : "";
            this.etaMinutes = etaMinutes;
            this.isSnapshotUpdate = isSnapshotUpdate;
            this.mapSnapshot = mapSnapshot;
        }

        public static NaviInfo idle() {
            return new NaviInfo(false, "", "", "", "", "", "", "", 0);
        }

        public static NaviInfo snapshot(@NonNull Bitmap bitmap) {
            return new NaviInfo(false, "", "", "", "", "", "", "", 0, true, bitmap);
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
            if (isMapSnapshotChannel(jsonString)) {
                handleMapSnapshot(jsonString);
                return;
            }
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
                        durationToMinutes(nav.getDuration())
                );
                mMainHandler.post(() -> mListener.onNaviDataChanged(info));
            } catch (JsonSyntaxException e) {
                Log.e(TAG, "onNaviDataReceived: JSON parse error", e);
            }
        }

        // Peeks the envelope's top-level "channel" field without fully parsing the payload as
        // either shape, so the existing TBT ("turn-by-turn") path below is unaffected.
        private boolean isMapSnapshotChannel(String jsonString) {
            try {
                JSONObject envelope = new JSONObject(jsonString);
                return CHANNEL_MAP_SNAPSHOT.equals(envelope.optString("channel", null));
            } catch (JSONException e) {
                return false;
            }
        }

        private void handleMapSnapshot(String jsonString) {
            try {
                JSONObject envelope = new JSONObject(jsonString);
                JSONObject data = envelope.getJSONObject("data");
                String base64 = data.getString("bitmap");
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) {
                    Log.w(TAG, "handleMapSnapshot: decodeByteArray returned null, base64Length="
                            + base64.length());
                    return;
                }
                Log.d(TAG, "handleMapSnapshot: received " + bitmap.getWidth() + "x"
                        + bitmap.getHeight() + " bitmap, base64Length=" + base64.length());
                NaviInfo info = NaviInfo.snapshot(bitmap);
                mMainHandler.post(() -> mListener.onNaviDataChanged(info));
            } catch (JSONException e) {
                Log.e(TAG, "handleMapSnapshot: JSON parse error", e);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "handleMapSnapshot: base64 decode error", e);
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
}
