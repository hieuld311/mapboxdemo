package com.ivi.launcher.view.dialog;

import android.util.Log;
import android.view.View;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ivi.launcher.R;
import com.ivi.launcher.view.dialog.BaseDialog;

import androidx.annotation.NonNull;

public class SelectAppFullModeDialog extends BaseDialog {
    private static final String TAG = "SelectAreaDialog";
    //action receive result selected
    private static final String ACTION_RECEIVE_RESULT_APP_SELECTED
            = "com.android.intent.action.ACTION_RECEIVE_RESULT_APP_SELECTED";
    private static final String KEY = "result";
    private LinearLayout mRootLayout;
    private View mPrimaryArea;
    private View mSecondaryArea;

    public SelectAppFullModeDialog(@NonNull Context context) {
        super(context);
    }

    @Override
    int getLayoutId() {
        return R.layout.dialog_select_app_full_mode;
    }

    @Override
    void initView() {
        Window window = getWindow();
        mPrimaryArea = window.findViewById(R.id.view_primary_area);
        mSecondaryArea = window.findViewById(R.id.view_secondary_area);
        mRootLayout = window.findViewById(R.id.linear_root);
    }

    @Override
    void initListener() {
        mPrimaryArea.setOnClickListener(v -> {
            setSelectedArea(true);
        });

        mSecondaryArea.setOnClickListener(v -> {
            setSelectedArea(false);
        });
        mRootLayout.setOnClickListener(v -> {
            if (isShowing()) {
                dismiss();
            }
        });
    }

    private void setSelectedArea(boolean area) {
        Intent intent = new Intent();
        intent.setAction(ACTION_RECEIVE_RESULT_APP_SELECTED);
        intent.putExtra(KEY, area);
        Log.d(TAG, "setSelectedArea area =  " + area);
        getContext().sendBroadcastAsUser(intent, UserHandle.CURRENT);
        dismiss();
    }
}
