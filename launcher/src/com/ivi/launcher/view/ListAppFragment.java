package com.ivi.launcher.view;


import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
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
import android.text.format.DateUtils;
import android.util.Log;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup;
import androidx.recyclerview.widget.RecyclerView;

import com.ivi.launcher.R;
import com.ivi.launcher.model.AppMetaData;
import com.ivi.launcher.utils.AppLauncherUtils;
import com.ivi.launcher.utils.AppLauncherUtils.LauncherAppsInfo;
import com.ivi.launcher.view.adapter.AppGridAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.view.MotionEvent;

public class ListAppFragment extends Fragment {

    private static final String TAG = "ListAppFragment";

    private int mColumnNumber;

    private AppGridAdapter mGridAdapter;

    private UsageStatsManager mUsageStatsManager;
    private AppInstallUninstallReceiver mInstallUninstallReceiver;

    private RecyclerView gridView;

    private List<AppMetaData> apps;

    private int pageIndex = 1;

    public ListAppFragment(int pageIndex) {
        Log.d(TAG, "ListAppFragment: " + pageIndex);
        this.pageIndex = pageIndex;
    }

    public ListAppFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_list_app, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated for page: " + pageIndex);
        mColumnNumber = getResources().getInteger(R.integer.car_app_selector_column_number);
        mUsageStatsManager = (UsageStatsManager) requireContext().getSystemService(Context.USAGE_STATS_SERVICE);
        mGridAdapter = new AppGridAdapter(requireContext());
        gridView = view.findViewById(R.id.apps_grid);

        //gridView.setVerticalScrollBarEnabled(false);
        gridView.setOnTouchListener((v, event) -> (event.getAction() == MotionEvent.ACTION_MOVE));

        CustomGridLayoutManager gridLayoutManager = new CustomGridLayoutManager(requireContext(), mColumnNumber);
        gridLayoutManager.setSpanSizeLookup(new SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mGridAdapter.getSpanSizeLookup(position);
            }
        });
        gridView.setLayoutManager(gridLayoutManager);

        Log.d(TAG, "setAdapter for page: " + pageIndex);
        mGridAdapter.setOnItemClickListener(() -> {
//            ((IviLauncher) getActivity()).showSplashScreen();
        });
        gridView.setAdapter(mGridAdapter);
        updateAppsLists();
    }

    private List<AppMetaData> rearrangeForColumn(List<AppMetaData> original, int rowCount, int colCount) {
        List<AppMetaData> rearranged = new ArrayList<>(rowCount * colCount);

        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < colCount; col++) {
                int index = col * rowCount + row;
                if (index < original.size()) {
                    rearranged.add(original.get(index));
                } else {
                    rearranged.add(null); // fill with null
                }
            }
        }
        return rearranged;
    }

    /**
     * Updates the list of all apps, and the list of the most recently used ones.
     */
    private void updateAppsLists() {
        Log.d(TAG, "updateAppsLists start update app list ");
//        this.apps = ((IviLauncher) getActivity()).getAppsListByPageIndex(pageIndex);
//        Log.d(TAG, "updateAppsLists count: " + apps.size());
//        mGridAdapter.setAllApps(apps);
    }

    @Override
    public void onStart() {
        super.onStart();
        // register broadcast receiver for package installation and uninstallation
        mInstallUninstallReceiver = new AppInstallUninstallReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        requireContext().registerReceiver(mInstallUninstallReceiver, filter);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Using onResume() to refresh most recently used apps because we want to refresh even if
        // the app being launched crashes/doesn't cover the entire screen.
        Log.d(TAG, "onResume: ");
        updateAppsLists();
    }

    @Override
    public void onStop() {
        super.onStop();
        // disconnect from app install/uninstall receiver
        if (mInstallUninstallReceiver != null) {
            requireContext().unregisterReceiver(mInstallUninstallReceiver);
            mInstallUninstallReceiver = null;
        }
    }

    /**
     * Note that in order to obtain usage stats from the previous boot,
     * the device must have gone through a clean shut down process.
     */
    private List<AppMetaData> getMostRecentApps(LauncherAppsInfo appsInfo) {
        ArrayList<AppMetaData> apps = new ArrayList<>();
        if (appsInfo.isEmpty()) {
            return apps;
        }

        // get the usage stats starting from 1 year ago with a INTERVAL_YEARLY granularity
        // returning entries like:
        // "During 2017 App A is last used at 2017/12/15 18:03"
        // "During 2017 App B is last used at 2017/6/15 10:00"
        // "During 2018 App A is last used at 2018/1/1 15:12"
        List<UsageStats> stats =
                mUsageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_YEARLY,
                        System.currentTimeMillis() - DateUtils.YEAR_IN_MILLIS,
                        System.currentTimeMillis());

        if (stats == null || stats.size() == 0) {
            return apps; // empty list
        }

        stats.sort(new LastTimeUsedComparator());

        int currentIndex = 0;
        int itemsAdded = 0;
        int statsSize = stats.size();
        int itemCount = Math.min(mColumnNumber, statsSize);
        while (itemsAdded < itemCount && currentIndex < statsSize) {
            UsageStats usageStats = stats.get(currentIndex);
            String packageName = usageStats.mPackageName;
            currentIndex++;

            // do not include self
            if (packageName.equals(requireContext().getPackageName())) {
                continue;
            }

            AppMetaData app = appsInfo.getAppMetaData(packageName);
            // Prevent duplicated entries
            // e.g. app is used at 2017/12/31 23:59, and 2018/01/01 00:00
            if (app != null && !apps.contains(app)) {
                apps.add(app);
                itemsAdded++;
            }
        }
        return apps;
    }

    /**
     * Comparator for {@link UsageStats} that sorts the list by the "last time used" property
     * in descending order.
     */
    private static class LastTimeUsedComparator implements Comparator<UsageStats> {
        @Override
        public int compare(UsageStats stat1, UsageStats stat2) {
            Long time1 = stat1.getLastTimeUsed();
            Long time2 = stat2.getLastTimeUsed();
            return time2.compareTo(time1);
        }
    }

    private class AppInstallUninstallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String packageName = intent.getData().getSchemeSpecificPart();

            if (TextUtils.isEmpty(packageName)) {
                Log.e(TAG, "System sent an empty app install/uninstall broadcast");
                return;
            }
            updateAppsLists();
        }
    }
}