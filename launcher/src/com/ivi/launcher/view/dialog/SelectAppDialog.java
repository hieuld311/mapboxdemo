package com.ivi.launcher.view.dialog;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.car.Car;
import android.widget.TextView;
import android.car.CarNotConnectedException;
import android.car.content.pm.CarPackageManager;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.view.Window;

import com.ivi.launcher.utils.AppLauncherUtils;
import com.ivi.launcher.model.AppMetaData;
//import com.ivi.launcher.controller.LauncherController;
import com.ivi.launcher.R;
import com.ivi.launcher.view.adapter.AppsAdapter;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class SelectAppDialog extends BaseDialog {
    private static final String TAG = "SelectAppDialog";
    private TextView btnSelect, btnCancel;
    private RecyclerView mGridApp;
    private Intent appItem;
    private boolean mShowAllApps = false;
    private final Set<String> mHiddenApps = new HashSet<>();
    private AppsAdapter mAppsAdapter;
//    private final LauncherController mLauncherController
//            = LauncherController.getInstance();
    private AppsAdapter.OnAppClickListener mOnAppClickListener;
    private OnAppSelectedListener mOnAppSelectedListener;
    private PackageManager mPackageManager;
    private AppInstallUninstallReceiver mInstallUninstallReceiver;
    private BroadcastReceiver mBroadcastReceiver;
    private Car mCar;
    private CarUxRestrictionsManager mCarUxRestrictionsManager;
    private CarPackageManager mCarPackageManager;
    private final int mArea;

    @Override
    int getLayoutId() {
        return R.layout.dialog_select_app;
    }

    public interface OnAppSelectedListener {
        void onAppSelected();
    }

    public SelectAppDialog(Context context, int area) {
        super(context);
        mArea = area;
    }

    private final ServiceConnection mCarConnectionListener = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                mCarUxRestrictionsManager = (CarUxRestrictionsManager) mCar.getCarManager(
                        Car.CAR_UX_RESTRICTION_SERVICE);
                mCarPackageManager = (CarPackageManager) mCar.getCarManager(Car.PACKAGE_SERVICE);
                getAppList();
            } catch (CarNotConnectedException e) {
                Log.e(TAG, "Car not connected in CarConnectionListener", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mCarUxRestrictionsManager = null;
            mCarPackageManager = null;
        }
    };

    private void initBroadcast() {
        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "onReceive action: " + action);
                if ("ShowAllAppInSecondary".equals(action)) {
                    Log.d(TAG, "ShowAllAppInSecondary: " + action);
                    intent.setClassName("com.ivi.car.androidauto", "com.ivi.car.androidauto.MainActivity");
//                    mLauncherController.launchAppForAreaInternal(intent, 2, mContext);
                }
            }
        };
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("ShowAllAppInSecondary");
        mContext.registerReceiver(mBroadcastReceiver, intentFilter);
    }

    public void setOnAppSelectedListener(OnAppSelectedListener listener) {
        mOnAppSelectedListener = listener;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: ");
        mPackageManager = getContext().getPackageManager();
        setUpRecycleView();
        mCar = Car.createCar(getContext(), mCarConnectionListener);
        mHiddenApps.addAll(Arrays.asList(getContext().getResources()
                .getStringArray(R.array.hidden_apps)));
        initBroadcast();
    }

    @Override
    public void onDetachedFromWindow() {
        mContext.unregisterReceiver(mBroadcastReceiver);
        super.onDetachedFromWindow();
    }

    /**
     * Get a list of app which can be display in selected area
     */
    private void getAppList() {
//        ActivityManager.StackInfo visibleStack =
//                mLauncherController.getVisibleStack(mArea);
//        Log.d(TAG, "visibleStack: " + visibleStack);
//        Set<String> blackList = mShowAllApps ? Collections.emptySet() : mHiddenApps;
//        AppLauncherUtils.LauncherAppsInfo appsInfo =
//                AppLauncherUtils.getAllLauncherApps(blackList,
//                        getContext().getSystemService(LauncherApps.class),
//                        mCarPackageManager, mPackageManager);
//        List<AppMetaData> apps = appsInfo.getApplicationsList();
//        // This flag to check whether to check disable the app or not.
//        boolean needCheckPackage = true;
//        for (int i = 0; i < apps.size(); i++) {
//            AppMetaData app = apps.get(i);
//            if (needCheckPackage && visibleStack != null && app.getPackageName().equals(
//                    visibleStack.topActivity.getPackageName())) {
//                app.setDisable(true);
//                needCheckPackage = false;
//            }
//        }
//        mAppsAdapter.setAllApps(apps);
    }
    private void setUpRecycleView() {
        mAppsAdapter = new AppsAdapter(getContext());
        mAppsAdapter.setOnAppClickListener(mOnAppClickListener);
        LinearLayoutManager linearLayoutManager = new LinearLayoutManager(getContext(),
                LinearLayoutManager.VERTICAL, false);
        mGridApp.setLayoutManager(linearLayoutManager);
        mGridApp.setAdapter(mAppsAdapter);
    }
    @Override
    protected void onStart() {
        super.onStart();
        // register broadcast receiver for package installation and uninstallation
        Log.d(TAG, "onStart: ");
        mInstallUninstallReceiver = new AppInstallUninstallReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        getContext().registerReceiver(mInstallUninstallReceiver, filter);
        // Connect to car service
        mCar.connect();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // disconnect from app install/uninstall receiver
        Log.d(TAG, "onStop: ");
        if (mInstallUninstallReceiver != null) {
            getContext().unregisterReceiver(mInstallUninstallReceiver);
            mInstallUninstallReceiver = null;
        }
        // disconnect from car listeners
        try {
            if (mCarUxRestrictionsManager != null) {
                mCarUxRestrictionsManager.unregisterListener();
            }
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Error unregistering listeners", e);
        }
        if (mCar != null) {
            mCar.disconnect();
        }
    }

    private class AppInstallUninstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "onReceive: ");
            String packageName = intent.getData().getSchemeSpecificPart();
            if (TextUtils.isEmpty(packageName)) {
                Log.e(TAG, "System sent an empty app install/uninstall broadcast");
                return;
            }
            getAppList();
        }
    }

    @Override
    void initView() {
        Window window = getWindow();
        mGridApp = window.findViewById(R.id.rcv_apps);
        btnSelect = window.findViewById(R.id.btn_select);
        btnCancel = window.findViewById(R.id.btn_cancel);
    }

    @Override
    void initListener() {
        mOnAppClickListener = app -> appItem = app.getMainLaunchIntent();
        btnSelect.setOnClickListener(v -> {
            Log.d(TAG, "onAppClick: " + appItem);
//            mLauncherController.launchAppForAreaInternal(appItem, mArea, mContext);
            dismiss();
            if (mOnAppSelectedListener != null) {
                mOnAppSelectedListener.onAppSelected();
            }
        });
        btnCancel.setOnClickListener(v -> dismiss());
    }
}