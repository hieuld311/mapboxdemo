/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ivi.launcher.view.adapter;

import android.annotation.Nullable;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.ivi.launcher.utils.AppLauncherUtils;
import com.ivi.launcher.model.AppMetaData;
import com.ivi.launcher.view.viewholder.AppItemViewHolder;
import com.ivi.launcher.R;
import com.ivi.launcher.event.*;
import java.util.List;

/**
 * The adapter that populates the grid view with apps.
 */
public final class AppGridAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public static final int RECENT_APPS_TYPE = 1;
    public static final int APP_ITEM_TYPE = 2;
    private OnItemClickListener listener;

    private final Context mContext;
    private final int mColumnNumber;
    private final LayoutInflater mInflater;

    private List<AppMetaData> mApps;
    private List<AppMetaData> mMostRecentApps;
    private boolean mIsDistractionOptimizationRequired;

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public AppGridAdapter(Context context) {
        mContext = context;
        mInflater = LayoutInflater.from(context);
        mColumnNumber =
                mContext.getResources().getInteger(R.integer.car_app_selector_column_number);
    }

    public void setIsDistractionOptimizationRequired(boolean isDistractionOptimizationRequired) {
        mIsDistractionOptimizationRequired = isDistractionOptimizationRequired;
        notifyDataSetChanged();
    }

    public void setMostRecentApps(@Nullable List<AppMetaData> mostRecentApps) {
        mMostRecentApps = mostRecentApps;
        notifyDataSetChanged();
    }

    public void setAllApps(@Nullable List<AppMetaData> apps) {
        mApps = apps;
        notifyDataSetChanged();
    }

    public int getSpanSizeLookup(int position) {
        if (position == 0 && hasRecentlyUsedApps()) {
            return mColumnNumber;
        }
        return 1;
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0 && hasRecentlyUsedApps()) {
            return RECENT_APPS_TYPE;
        }
        return APP_ITEM_TYPE;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = mInflater.inflate(R.layout.app_item, parent, /* attachToRoot= */ false);
        return new AppItemViewHolder(view, mContext, listener);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        switch (holder.getItemViewType()) {
            case APP_ITEM_TYPE:
                int index = hasRecentlyUsedApps() ? position - 1 : position;
                AppMetaData app = mApps.get(index);
                ((AppItemViewHolder) holder).bind(app, mIsDistractionOptimizationRequired);
                break;
            default:
        }
    }

    @Override
    public int getItemCount() {
        // If there are any most recently launched apps, add a "most recently used apps row item"
        return (mApps == null ? 0 : mApps.size()) + (hasRecentlyUsedApps() ? 1 : 0);
    }

    private boolean hasRecentlyUsedApps() {
        return mMostRecentApps != null && mMostRecentApps.size() > 0;
    }
}