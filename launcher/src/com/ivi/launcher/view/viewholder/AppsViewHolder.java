package com.ivi.launcher.view.viewholder;

import android.annotation.Nullable;
import android.content.Context;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.ivi.launcher.model.AppMetaData;

import androidx.recyclerview.widget.RecyclerView;

import com.ivi.launcher.R;

/**
 * App item view holder that contains the app icon and name.
 */
public class AppsViewHolder extends RecyclerView.ViewHolder {

    private final Context mContext;
    private View mAppItem;
    private ImageView mAppIconView;
    private TextView mAppNameView;
    private int mColorDisable;
    private int mColorEnable;

    public AppsViewHolder(View view, Context context) {
        super(view);
        mContext = context;
        mAppItem = view.findViewById(R.id.app_item);
        mAppIconView = mAppItem.findViewById(R.id.app_icon);
        mAppNameView = mAppItem.findViewById(R.id.app_name);
        mColorDisable = mContext.getColor(R.color.app_disable_color);
        mColorEnable = mContext.getColor(R.color.app_enable_color);
    }

    /**
     * Binds the grid app item view with the app meta data.
     *
     * @param app Pass {@code null} will empty out the view.
     */
    public void bind(@Nullable AppMetaData app) {
        if (app == null) {
            return;
        }
        mAppNameView.setText(app.getDisplayName());
        mAppIconView.setImageDrawable(app.getIcon());
        if (app.isDisable()) {
            mAppIconView.setEnabled(false);
            mAppNameView.setEnabled(false);
        } else {
            mAppIconView.setEnabled(true);
            mAppNameView.setEnabled(true);
        }
    }
}
