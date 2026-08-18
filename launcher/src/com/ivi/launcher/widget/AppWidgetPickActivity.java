package com.ivi.launcher.widget;


import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;

import com.ivi.launcher.constant.LauncherConstant;

import java.util.List;

/**
 * Displays a list of {@link AppWidgetProviderInfo} widgets, along with any
 * injected special widgets specified through
 * {@link AppWidgetManager#EXTRA_CUSTOM_INFO} and
 * {@link AppWidgetManager#EXTRA_CUSTOM_EXTRAS}.
 * <p>
 * When an installed {@link AppWidgetProviderInfo} is selected, this activity
 * will bind it to the given {@link AppWidgetManager#EXTRA_APPWIDGET_ID},
 * otherwise it will return the requested extras.
 */
public class AppWidgetPickActivity extends ActivityPicker
        implements AppWidgetLoader.ItemConstructor<ActivityPicker.PickAdapter.Item>{
    private static final String TAG = "AppWidgetPickActivity";
    static final boolean LOGD = false;
    private int mArea = -1;
    List<PickAdapter.Item> mItems;

    /**
     * The allocated {@link AppWidgetManager#EXTRA_APPWIDGET_ID} that this
     * activity is binding.
     */
    private int mAppWidgetId;
    private AppWidgetLoader<PickAdapter.Item> mAppWidgetLoader;
    private AppWidgetManager mAppWidgetManager;
    private PackageManager mPackageManager;

    @Override
    public void onCreate(Bundle icicle) {
        mPackageManager = getPackageManager();
        mAppWidgetManager = AppWidgetManager.getInstance(this);
        mAppWidgetLoader = new AppWidgetLoader<PickAdapter.Item>
                (this, mAppWidgetManager, this);

        super.onCreate(icicle);

        // Set default return data
        setResultData(RESULT_CANCELED, null);

        // Read the appWidgetId passed our direction, otherwise bail if not found
        final Intent intent = getIntent();
        if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
            mAppWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
            mArea = intent.getExtras().getInt(LauncherConstant.AREA, -1);
        } else {
            finish();
        }
    }

    /**
     * Build and return list of items to be shown in dialog. This will mix both
     * installed {@link AppWidgetProviderInfo} and those provided through
     * {@link AppWidgetManager#EXTRA_CUSTOM_INFO}, sorting them alphabetically.
     */
    @Override
    protected List<PickAdapter.Item> getItems() {
        mItems = mAppWidgetLoader.getItems(getIntent());
        return mItems;
    }

    @Override
    public PickAdapter.Item createItem(Context context, AppWidgetProviderInfo info, Bundle extras) {
        CharSequence label = info.label;
        Drawable icon = null;

        if (info.icon != 0) {
            try {
                final Resources res = context.getResources();
                final int density = res.getDisplayMetrics().densityDpi;
                int iconDensity;
                switch (density) {
                    case DisplayMetrics.DENSITY_MEDIUM:
                        iconDensity = DisplayMetrics.DENSITY_LOW;
                    case DisplayMetrics.DENSITY_TV:
                        iconDensity = DisplayMetrics.DENSITY_MEDIUM;
                    case DisplayMetrics.DENSITY_HIGH:
                        iconDensity = DisplayMetrics.DENSITY_MEDIUM;
                    case DisplayMetrics.DENSITY_XHIGH:
                        iconDensity = DisplayMetrics.DENSITY_HIGH;
                    case DisplayMetrics.DENSITY_XXHIGH:
                        iconDensity = DisplayMetrics.DENSITY_XHIGH;
                    default:
                        // The density is some abnormal value.  Return some other
                        // abnormal value that is a reasonable scaling of it.
                        iconDensity = (int)((density*0.75f)+.5f);
                }
                Resources packageResources = mPackageManager.
                        getResourcesForApplication(info.provider.getPackageName());
                icon = packageResources.getDrawableForDensity(info.icon, iconDensity);
            } catch (NameNotFoundException e) {
                Log.w(TAG, "Can't load icon drawable 0x" + Integer.toHexString(info.icon)
                        + " for provider: " + info.provider);
            }
            if (icon == null) {
                Log.w(TAG, "Can't load icon drawable 0x" + Integer.toHexString(info.icon)
                        + " for provider: " + info.provider);
            }
        }

        PickAdapter.Item item = new PickAdapter.Item(context, label, icon);
        item.packageName = info.provider.getPackageName();
        item.className = info.provider.getClassName();
        item.extras = extras;
        return item;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onClick(DialogInterface dialog, int which) {
        Intent intent = getIntentForPosition(which);
        Log.d(TAG, "onClick: " + intent);
        Log.d(TAG, "onClick0: " + intent.getComponent());
        PickAdapter.Item item = mItems.get(which);

        int result;
        if (item.extras != null) {
            // If these extras are present it's because this entry is custom.
            // Don't try to bind it, just pass it back to the app.
            setResultData(RESULT_OK, intent);
        } else {
            try {
                Bundle options = null;
                if (intent.getExtras() != null) {
                    options = intent.getExtras().getBundle(
                            AppWidgetManager.EXTRA_APPWIDGET_OPTIONS);
                }
                mAppWidgetManager.bindAppWidgetId(mAppWidgetId, intent.getComponent(), null);
                result = RESULT_OK;
            } catch (IllegalArgumentException e) {
                // This is thrown if they're already bound, or otherwise somehow
                // bogus.  Set the result to canceled, and exit.  The app *should*
                // clean up at this point.  We could pass the error along, but
                // it's not clear that that's useful -- the widget will simply not
                // appear.
                result = RESULT_CANCELED;
            }
            setResultData(result, null);
        }

        finish();
    }

    /**
     * Convenience method for setting the result code and intent. This method
     * correctly injects the {@link AppWidgetManager#EXTRA_APPWIDGET_ID} that
     * most hosts expect returned.
     */
    void setResultData(int code, Intent intent) {
        Intent result = intent != null ? intent : new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mAppWidgetId);
        result.putExtra(LauncherConstant.AREA, mArea);
        setResult(code, result);
    }
}
