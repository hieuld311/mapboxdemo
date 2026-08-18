package com.ivi.launcher.view.adapter;

import android.annotation.Nullable;
import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import com.ivi.launcher.utils.AppLauncherUtils;
import com.ivi.launcher.model.AppMetaData;
import com.ivi.launcher.R;
import com.ivi.launcher.view.viewholder.AppsViewHolder;

import java.util.Collections;
import java.util.List;

import androidx.recyclerview.widget.RecyclerView;

/**
 * The adapter that populates the grid view with apps.
 */
public final class AppsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final String TAG = "AppsAdapter";
    private final Context mContext;
    private final LayoutInflater mInflater;
    private OnAppClickListener mOnAppClickListener;
    private List<AppMetaData> mApps;

    public AppsAdapter(Context context) {
        mContext = context;
        mInflater = LayoutInflater.from(context);
    }

    public void setOnAppClickListener(OnAppClickListener onAppClickListener) {
        mOnAppClickListener = onAppClickListener;
    }

    public void setAllApps(@Nullable List<AppMetaData> apps) {
        mApps = apps;
        sortAllApps();
        notifyDataSetChanged();
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = mInflater.inflate(R.layout.item_app, parent, /* attachToRoot= */ false);
        AppsViewHolder appsViewHolder = new AppsViewHolder(view, mContext);
//        int position = appsViewHolder.getAdapterPosition();
//        appsViewHolder.itemView.setOnClickListener(v -> {
//            AppMetaData item = mApps.get(appsViewHolder.getAdapterPosition());
//            if (!item.isDisable()) {
//                mOnAppClickListener.onAppClick(item);
//            }
//        });

        appsViewHolder.itemView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()){
                    case MotionEvent.ACTION_DOWN:
                        view.requestFocus();
                        view.setPressed(true);
                        break;
                    case MotionEvent.ACTION_UP:
//                        view.setPressed(false);
                        AppMetaData item = mApps.get(appsViewHolder.getAdapterPosition());
                        if (!item.isDisable()) {
                            mOnAppClickListener.onAppClick(item);
                        }
                        break;
                }
                return true;
            }
        });
        return appsViewHolder;
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        AppMetaData app = mApps.get(position);
        ((AppsViewHolder) holder).bind(app);
    }

    @Override
    public int getItemCount() {
        return mApps.size();
    }

    private void sortAllApps() {
        if (mApps != null) {
            Collections.sort(mApps, AppLauncherUtils.ALPHABETICAL_COMPARATOR);
        }
    }

    public interface OnAppClickListener {
        public void onAppClick(AppMetaData app);
    }
}
