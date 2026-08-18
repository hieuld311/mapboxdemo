package com.ivi.launcher.view.dialog;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.UserHandle;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.view.MotionEvent;

import com.ivi.launcher.R;
import com.ivi.launcher.constant.LauncherConstant;

import androidx.annotation.NonNull;

public class SelectAreaDialog extends BaseDialog {
    private static final String TAG = "SelectAreaDialog";
    private View mPrimaryArea;
    private View mSecondaryArea;
    private LinearLayout mRootLayout;
    private SelectAppDialog mSelectAppDialog;

    @Override
    int getLayoutId() {
        return R.layout.dialog_select_area;
    }

    public SelectAreaDialog(@NonNull Context context) {
        super(context);
    }

    private SelectAppDialog.OnAppSelectedListener mOnAppSelectedListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initView();
        initListener();
    }

    @Override
    void initView() {
        Window window = getWindow();
        mPrimaryArea = window.findViewById(R.id.view_primary_area);
        mSecondaryArea = window.findViewById(R.id.view_secondary_area);
        mRootLayout = window.findViewById(R.id.linear_root);
        mPrimaryArea.requestFocus();
    }

    private void initSelectAppDialog(int area) {
        mSelectAppDialog = new SelectAppDialog(getContext(), area);
        mSelectAppDialog.setOnAppSelectedListener(mOnAppSelectedListener);
        mSelectAppDialog.setCancelable(true);
        mSelectAppDialog.setCanceledOnTouchOutside(true);
        mSelectAppDialog.setOwnerActivity(getOwnerActivity());
        mSelectAppDialog.show();
    }

    @Override
    void initListener() {
        mRootLayout.setOnClickListener(v -> dismiss());
        mOnAppSelectedListener = this::dismiss;
        mPrimaryArea.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    view.requestFocus();
                    view.setPressed(true);
                    break;
                case MotionEvent.ACTION_UP:
                    initSelectAppDialog(LauncherConstant.PRIMARY_AREA);
                    Intent intent = new Intent();
                    intent.setAction("Action.From.Launcher");
                    getContext().sendBroadcastAsUser(intent, UserHandle.CURRENT);
                    view.setPressed(false);
                    break;
            }
            return true;
        });

        mSecondaryArea.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    view.requestFocus();
                    view.setPressed(true);
                    break;
                case MotionEvent.ACTION_UP:
                    initSelectAppDialog(LauncherConstant.SECONDARY_AREA);
                    view.setPressed(false);
                    break;
            }
            return true;
        });
    }

    @Override
    public void dismiss() {
        super.dismiss();
        if (mSelectAppDialog != null && mSelectAppDialog.isShowing()) {
            mSelectAppDialog.dismiss();
        }
    }
}
