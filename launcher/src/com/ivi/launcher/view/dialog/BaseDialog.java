package com.ivi.launcher.view.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

abstract class BaseDialog extends Dialog {
    public Context mContext;
    abstract int getLayoutId();

    public BaseDialog(@NonNull Context context) {
        super(context);
        mContext = context;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupDialog();
        initView();
        initListener();
    }

    private void setupDialog() {
        Window window = getWindow();
        window.requestFeature(Window.FEATURE_NO_TITLE);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        window.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
        window.setWindowAnimations(com.android.internal.R.style.Animation_Toast);
        final WindowManager.LayoutParams lp = window.getAttributes();
        lp.format = PixelFormat.TRANSLUCENT;
        lp.gravity = Gravity.CENTER | Gravity.CENTER_HORIZONTAL;
        lp.windowAnimations = -1;
        window.setAttributes(lp);

        setContentView(getLayoutId());
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    abstract void initView();

    abstract void initListener();
}
