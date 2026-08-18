package com.ivi.launcher.view.viewholder;

import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.ivi.launcher.R;
import com.ivi.launcher.model.AppMetaData;
import com.ivi.launcher.utils.AppLauncherUtils;
import com.ivi.launcher.event.*;

/**
 * App item view holder that contains the app icon and name.
 */
public class AppItemViewHolder extends RecyclerView.ViewHolder {

    private static final String TAG = "AppItemViewHolder";

    private final Context mContext;
    private final View mAppItem;
    private final ImageView mAppIconView;
    private final TextView mAppNameView;
    private OnItemClickListener listener;

    public AppItemViewHolder(View view, Context context, OnItemClickListener Ilistener) {
        super(view);
        mContext = context;
        mAppItem = view.findViewById(R.id.app_item);
        mAppIconView = mAppItem.findViewById(R.id.app_icon);
        mAppNameView = mAppItem.findViewById(R.id.app_name);
        listener = Ilistener;
    }

    public void bind(@Nullable AppMetaData app, boolean isDistractionOptimizationRequired) {
        // Reset view state
        mAppIconView.setImageDrawable(null);
        mAppNameView.setText(null);

        if (app == null) {
            mAppItem.setVisibility(View.GONE);
            return;
        }

        mAppItem.setVisibility(View.VISIBLE);

        String packageName = app.getPackageName();
        String displayName = AppLauncherUtils.getCustomAppName(packageName, app.getDisplayName());
        mAppNameView.setText(displayName.toUpperCase());

        // Set icon
        Integer iconRes = AppLauncherUtils.getAppIconResource(packageName);
        if (iconRes != -1) {
            mAppIconView.setImageResource(iconRes);
        } else {
            mAppIconView.setImageDrawable(app.getIcon());
            Log.d(TAG, "Unknown app icon for: " + packageName);
        }

        boolean isLaunchable = !isDistractionOptimizationRequired || app.getIsDistractionOptimized();
        float opacity = mContext.getResources().getFloat(isLaunchable ? R.dimen.app_icon_opacity : R.dimen.app_icon_opacity_unavailable);
        mAppIconView.setAlpha(opacity);

        if (isLaunchable) {
            mAppItem.setOnClickListener(v -> {
                listener.onItemClick();
                AppLauncherUtils.launchApp(mContext, app);
            });
            mAppItem.setLongClickable(app.getAlternateLaunchIntent() != null);
            mAppItem.setOnLongClickListener(v -> openAlternateLaunchIntent(app));
        } else {
            showToastOnClick(mContext.getString(R.string.driving_toast_text, app.getDisplayName()));
        }

        if (app.isDisable()) {
            Log.d(TAG, "bind: app " + packageName);
            mAppIconView.setAlpha(mContext.getResources().getFloat(R.dimen.app_icon_opacity_unavailable));
            mAppNameView.setEnabled(false);
            showToastOnClick(mContext.getString(R.string.can_not_start, app.getDisplayName()));
        }
    }

    private boolean openAlternateLaunchIntent(AppMetaData app) {
        Intent intent = app.getAlternateLaunchIntent();
        if (intent != null) {
            mContext.startActivity(intent);
            return true;
        }
        return false;
    }

    private void showToastOnClick(String message) {
        mAppItem.setOnClickListener(v -> Toast.makeText(mContext, message, Toast.LENGTH_LONG).show());
    }
}