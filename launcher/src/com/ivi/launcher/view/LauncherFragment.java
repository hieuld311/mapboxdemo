package com.ivi.launcher.view;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.TaskStackListener;
import android.car.Car;
import android.car.app.CarActivityManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.content.IntentFilter;
import android.provider.Settings;
import android.view.Display;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.constraintlayout.widget.Guideline;
import androidx.transition.AutoTransition;
import androidx.transition.Transition;
import androidx.transition.TransitionManager;

import com.github.penfeizhou.animation.apng.APNGDrawable;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.content.SharedPreferences;
import android.widget.Toast;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.graphics.Rect;
import android.os.UserManager;
import android.widget.HorizontalScrollView;
import android.net.ConnectivityManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.app.AlarmManager;
import com.android.wm.shell.TaskView;
import com.android.wm.shell.common.HandlerExecutor;
import com.android.car.internal.common.UserHelperLite;
import com.android.internal.annotations.VisibleForTesting;
import java.util.List;
import java.util.Random;
import java.util.Calendar;
import java.util.concurrent.atomic.AtomicReference;
import androidx.collection.ArraySet;
import com.ivi.launcher.constant.LauncherConstant;
import com.ivi.launcher.view.taskstack.TaskStackChangeListeners;
import com.ivi.launcher.view.dialog.SelectAppFullModeDialog;
import com.ivi.launcher.view.dialog.SelectAreaDialog;
import com.ivi.launcher.view.TaskViewManager;
import com.ivi.launcher.widget.AppWidgetContainerView;
import com.ivi.launcher.*;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.ivi.launcher.model.Navigation;
import android.os.IBinder;
import android.content.ServiceConnection;
import android.os.RemoteException;
import com.ivi.car.navigation.INaviListener;
import com.ivi.car.navigation.NaviAidlInterface;
import android.animation.ObjectAnimator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class LauncherFragment extends Fragment implements View.OnClickListener {
    private static final String TAG = "LauncherFragment";
    private static final boolean DEBUG = true;
    static final int DISCOVER_APPWIDGET_REQUEST = 1;
    static final int CONFIGURE_APPWIDGET_REQUEST = 2;
    static final String PENDING_APPWIDGET_ID = "pending_appwidget";
    private static final String SCHEME_PACKAGE = "package";
    //action show start all app
    private static final String ACTION_SHOW_ALL_APPS =
            "ivi.car.intent.action.SHOW_ALL_APPS";
    //action show start dialer
    private static final String ACTION_START_DIALER =
            "ivi.car.intent.action.START_DIALER";
    //action show start media
    private static final String ACTION_START_MEDIA =
            "ivi.car.intent.action.START_MEDIA";
    //action show start maps
    private static final String ACTION_START_MAPS =
            "com.android.intent.action.ACTION_START_MAPS";
    //action show select app full mode
    private static final String ACTION_SHOW_SELECT_APP_FULL_MODE_DIALOG =
            "com.android.intent.action.ACTION_SHOW_SELECT_APP_FULL_MODE_DIALOG";
    //action select area dialog
    private static final String ACTION_SHOW_SELECT_AREA_DIALOG =
            "com.android.intent.action.ACTION_SHOW_SELECT_AREA_DIALOG";
    //action start appgrid with option
    private static final String ACTION_START_APPGRID_WITH_OPTION =
            "ShowAllAppInSecondary";
    private static final String DEFAULT_TIME_ZONE = "Asia/Bangkok";
    private WifiManager mWifiManager;
    private ConnectivityManager mConnectivityManager;
    private Handler mHandler;
    //    private LauncherController mLauncherController
//            = LauncherController.getInstance();
    private boolean mIsStarted = false;
    private boolean mIsSetupWidget = false;
    private SelectAppFullModeDialog mSelectAppFullModeDialog;
    private SelectAreaDialog mSelectAreaDialog;
    private BroadcastReceiver mBroadcastReceiver;
    private BroadcastReceiver mLocalBroadcastReceiver;
    private AppWidgetManager mAppWidgetManager;
    private AppWidgetContainerView mAppWidgetArea2, mAppWidgetArea3;
    private LinearLayout mMediaWidgetContainerView;
    private LinearLayout mWeatherWidgetContainerView;
    private ImageView mAddAppwidget2, mAddAppwidget3;

    private ConstraintLayout mAssistantContainerView;
    private ImageView mAssistantImageView;
    private ImageView mLoadingImageView;
    private TextView mAssistantQuoteTextView;
    private TextView mGreetingTextView;
    private ViewGroup mapsCard;
    private boolean isInit = false;

    private ImageView animationCarBackground;
    private ConstraintLayout clBackgroundIv;
    private ConstraintLayout clNavIcon;
    private ConstraintLayout clMainLauncher;
    private ConstraintLayout clMapLayout;
    // Guideline start of car background image
    private Guideline glCarBackground;
    // Guideline between Map and Media
    private Guideline glWidgetEndMaps;
    // Guideline start of map
    private Guideline glMapStart;
    // Guideline bottom of map
    private Guideline glMapBottom;
    private ImageView mOpenMapButton;
    private ImageView ivNaviSpinning;
    private ImageView mMapShadow;
    private View navDestinationSide;
    private View navDestinationBottom;

    private Handler handlerQuotes = new Handler();
    private Runnable mRunnableQuotes;

    private Boolean isMapOpened = false;

    private NaviAidlInterface naviAidl;

    private final INaviListener.Stub listener = new INaviListener.Stub() {
        @Override
        public void onNaviDataReceived(String jsonString) {
            // Handle data sent from NavigationFragment
            Log.d("Launcher", "Received: " + jsonString);
            Gson gson = new Gson();

            try {
                // Convert the JSON string into a Navigation object
                Navigation navigationData = gson.fromJson(jsonString, Navigation.class);
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showTurnByTurnInfo(navDestinationSide, navigationData);
                        showTurnByTurnInfo(navDestinationBottom, navigationData);
                    }
                });
            } catch (JsonSyntaxException e) {
                System.err.println("Error parsing JSON: " + e.getMessage());
            }
        }
    };

    private void showTurnByTurnInfo(View nav, Navigation navData) {
        TextView tvTbtFinish = nav.findViewById(R.id.tvTbtFinish);
        TextView tvTbtDirection = nav.findViewById(R.id.tvTbtDirection);
        TextView tvTbtDistance = nav.findViewById(R.id.tvTbtDistance);
        TextView tvTbtPosition = nav.findViewById(R.id.tvTbtPosition);
        ImageView ivTbtSideBar = nav.findViewById(R.id.ivTbtSideBar);
        ImageView ivTbtBottomBar = nav.findViewById(R.id.ivTbtBottomBar);
        ImageView ivDestination = nav.findViewById(R.id.ivDestination);
        ConstraintLayout defaultViewCl = nav.findViewById(R.id.defaultViewCl);

        defaultViewCl.setVisibility(View.GONE);

        double distance = navData.getStepDistance();
        String type = camelCase(navData.getType());
        tvTbtDirection.setText(type);

        String unit = "";
        String stepUnit = navData.getStepUnit();
        if (stepUnit.equalsIgnoreCase("meters")) {
            unit = "m";
        } else {
            unit = "km";
        }

        tvTbtDistance.setText(validateDistance(distance) + unit);
        tvTbtPosition.setText(navData.getStepRoad());
        tvTbtFinish.setText(navData.getStepRoad());

        String typeStr = navData.getType();
        Log.d("Launcher", "typeStr: " + typeStr);

        int drawable = -1;

        if (typeStr.equals("U_TURN_RIGHT")) {
            drawable = R.drawable.ico_launcher_turn_by_turn_turn_u_turn_r_s;
        } else if (typeStr.equals("U_TURN_LEFT")) {
            drawable = R.drawable.ico_launcher_turn_by_turn_turn_u_turn_l_s;
        } else if (typeStr.equals("TURN_NORMAL_RIGHT")) {
            drawable = R.drawable.ico_launcher_turn_by_turn_turn_right_s;
        } else if (typeStr.equals("TURN_NORMAL_LEFT")) {
            drawable = R.drawable.ico_launcher_turn_by_turn_turn_left_s;
        } else if (typeStr.equals("TURN_SHARP_RIGHT")) {
            drawable = R.drawable.ico_launcher_turn_by_turn_turn_sharp_r_s;
        } else if (typeStr.equals("TURN_SHARP_LEFT")) {
            drawable = R.drawable.ico_launcher_turn_by_turn_turn_sharp_l_s;
        } else if (typeStr.equals("U_TURN")) {
            drawable = R.drawable.ico_launcher_turn_by_turn_turn_u_turn_s;
        } else if (typeStr.equals("TURN_SLIGHT_RIGHT")) {
            drawable = R.drawable.ico_launcher_turn_by_turn_turn_slight_r_s;
        } else if (typeStr.equals("TURN_SLIGHT_LEFT")) {
            drawable = R.drawable.ico_launcher_turn_by_turn_turn_slight_l_s;
        } else if (typeStr.equals("ROUNDABOUT_ENTER")) {
            drawable = R.drawable.ico_launcher_turn_by_turn_roundabout_s;
        } else if (typeStr.equals("DESTINATION")) {
            drawable = R.drawable.ico_launcher_turn_by_turn_turn_finish_s;
        } else if (typeStr.equals("UNKNOWN")) {
            drawable = R.drawable.ico_launcher_turn_by_turn_unknown_s;
        }
        if (drawable != -1) {
            ivTbtSideBar.setImageResource(drawable);
            ivTbtBottomBar.setImageResource(drawable);
        }
        if (distance == 0 && typeStr.equals("DESTINATION")) {
            tvTbtFinish.setVisibility(View.VISIBLE);
            ivDestination.setVisibility(View.VISIBLE);
            tvTbtPosition.setVisibility(View.INVISIBLE);
            tvTbtDirection.setVisibility(View.INVISIBLE);
            tvTbtDistance.setVisibility(View.INVISIBLE);
            ivTbtSideBar.setAlpha(0f);
            ivTbtBottomBar.setAlpha(0f);
        } else {
            ivDestination.setVisibility(View.GONE);
            tvTbtFinish.setVisibility(View.INVISIBLE);
            tvTbtPosition.setVisibility(View.VISIBLE);
            tvTbtDirection.setVisibility(View.VISIBLE);
            tvTbtDistance.setVisibility(View.VISIBLE);
            ivTbtSideBar.setAlpha(1f);
            ivTbtBottomBar.setAlpha(1f);
        }
    }

    private String validateDistance(double distance) {
        String str = Double.toString(distance);
        if (str.endsWith(".0")) {
            str = str.substring(0, str.length() - 2);
        }
        return str;
    }

    private String camelCase(String input) {
        String spaced = input.replace("_", " ");
        String[] words = spaced.split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.length() > 0) {
                result.append(word.substring(0, 1).toUpperCase());
                result.append(word.substring(1).toLowerCase());
                result.append(" ");
            }
        }

        String output = result.toString().trim();
        return output;
    }

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            naviAidl = NaviAidlInterface.Stub.asInterface(service);
            try {
                naviAidl.registerListener(listener);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            naviAidl = null;
        }
    };

    private int startScrollX = 0;
    private int scrollX = 0;
    private int hsvWidth = 0;
    private boolean mTaskViewReady;
    private TaskViewManager mTaskViewManager = null;
    private UserManager mUserManager;
    private TaskView mTaskView;
    private final AtomicReference<CarActivityManager> mCarActivityManagerRef =
            new AtomicReference<>();
    // Modification date 29-07-2023 19:00PM
    // Tracking this to check if the task in TaskView has crashed in the background.
    private int mTaskViewTaskId = INVALID_TASK_ID;
    private int mCarLauncherTaskId = INVALID_TASK_ID;
    private ActivityManager mActivityManager;
    // The callback methods in {@code mTaskViewListener} are running under MainThread.
    private final TaskView.Listener mTaskViewListener = new TaskView.Listener() {
        @Override
        public void onInitialized() {
            mTaskViewReady = true;
            Log.d(TAG, "startMapsInTaskView 3");
            startMapsInTaskView();
        }
        @Override
        public void onReleased() {
            mTaskViewReady = false;
        }
        @Override
        public void onTaskCreated(int taskId, ComponentName name) {
            if (DEBUG) Log.d(TAG, "onTaskCreated: taskId=" + taskId);
            mTaskViewTaskId = taskId;
        }
        @Override
        public void onTaskRemovalStarted(int taskId) {
            if (DEBUG) Log.d(TAG, "onTaskRemovalStarted: taskId=" + taskId);
            mTaskViewTaskId = INVALID_TASK_ID;
            // Don't restart the crashed Maps automatically, because it hinders lots of MultiXXX
            // CTS tests which cleans up all tasks but Home, then monitor Activity state
            // changes. If it restarts Maps, which causes unexpected Activity state changes.
        }
    };
    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) {
            boolean launcherFocused = taskId == mCarLauncherTaskId && focused;
            if (DEBUG) {
                // Log.d(TAG, "onTaskFocusChanged: taskId=" + taskId
                //         + ", launcherFocused=" + launcherFocused
                //         + ", mTaskViewTaskId=" + mTaskViewTaskId);
            }
            if (!launcherFocused) {
                return;
            }
            if (mTaskViewTaskId == INVALID_TASK_ID) {
                // If the task in TaskView is crashed during CarLauncher is background,
                // We'd like to restart it when CarLauncher becomes foreground and focused.
                Log.d(TAG, "startMapsInTaskView 2");
                startMapsInTaskView();
            }
        }
        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                                             boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
            mCarLauncherTaskId = requireActivity().getTaskId();
            Log.d(TAG, "onActivityRestartAttempt: taskId=" + task.taskId
                    + ", homeTaskVisible=" + homeTaskVisible + ", wasVisible=" + wasVisible
                    + ", mTaskViewTaskId=" + mTaskViewTaskId
                    + ", mCarLauncherTaskId=" + mCarLauncherTaskId);
            if (!homeTaskVisible && mTaskViewTaskId == task.taskId) {
                // The embedded map component received an intent, therefore forcibly bringing the
                // launcher to the foreground.
                bringToForeground();
                if (mMediaWidgetContainerView != null) {
                    mMediaWidgetContainerView.setVisibility(View.GONE);
                }
                if (mWeatherWidgetContainerView != null) {
                    mWeatherWidgetContainerView.setVisibility(View.GONE);
                }
                return;
            }
            if (homeTaskVisible && mCarLauncherTaskId == task.taskId) {
                Log.d(TAG, "startMapsInTaskView 1");
                startMapsInTaskView();
                // if (mMediaWidgetContainerView != null) {
                //     mMediaWidgetContainerView.setVisibility(View.VISIBLE);
                // }
            }
        }
    };
    //END
    private void release() {
        if (mTaskView != null && mTaskViewReady) {
            mTaskView.release();
            mTaskView = null;
        }
        if (mTaskViewManager != null) {
            mTaskViewManager.release();
            mTaskViewManager = null;
        }
    }
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView");
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_launcher, container, false);
    }
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Log.d(TAG, "onViewCreated");
        Settings.Global.putInt(requireActivity().getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 1); //TCC_COLDBOOT
        // Modification date 29-07-2023 19:00PM
        Car.createCar(/* context= */ getActivity().getApplicationContext(), /* handler= */ null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                (car, ready) -> {
                    if (!ready) {
                        Log.w(TAG, "CarService looks crashed");
                        mCarActivityManagerRef.set(null);
                        return;
                    }
                });
        mActivityManager = getActivity().getApplicationContext().getSystemService(ActivityManager.class);
        mUserManager = getActivity().getApplicationContext().getSystemService(UserManager.class);
        // Setting as trusted overlay to let touches pass through.
        requireActivity().getWindow().addPrivateFlags(PRIVATE_FLAG_TRUSTED_OVERLAY);
        // To pass touches to the underneath task.
        requireActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        mapsCard = getView().findViewById(R.id.maps_card);
        setUpTaskView(mapsCard);
        // END
        IntentFilter packageIntentFilter = new IntentFilter(Intent.ACTION_PACKAGE_REPLACED);
        packageIntentFilter.addDataScheme(SCHEME_PACKAGE);
        setUpDialog();
        initBroadcast();
        setAppWidgetView();
        setUpNavigationAnimation();

        Intent intent = new Intent("com.ivi.car.navigation.service.NaviAIDLService");
        intent.setPackage("com.ivi.car.navigation");
        getActivity().bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    private void setUpNavigationAnimation() {
        ImageView sideBarBottomIv = navDestinationSide.findViewById(R.id.ivTbtBottomBar);
        ImageView sideBarSideIv = navDestinationSide.findViewById(R.id.ivTbtSideBar);
        ImageView bottomBarBottomIv = navDestinationBottom.findViewById(R.id.ivTbtBottomBar);
        ImageView bottomBarSideIv = navDestinationBottom.findViewById(R.id.ivTbtSideBar);
        ImageView ivSideNavIcon = navDestinationBottom.findViewById(R.id.ivSideNavIcon);
        TextView sideBarSideTvDirection = navDestinationSide.findViewById(R.id.tvTbtDirection);
        TextView bottomBarSideTvDirection = navDestinationBottom.findViewById(R.id.tvTbtDirection);
        TextView sideBarSideTvPosition = navDestinationSide.findViewById(R.id.tvTbtPosition);
        TextView bottomBarSideTvPosition = navDestinationBottom.findViewById(R.id.tvTbtPosition);
        TextView sideBarSideTvFinish = navDestinationSide.findViewById(R.id.tvTbtFinish);
        TextView bottomBarSideTvFinish = navDestinationBottom.findViewById(R.id.tvTbtFinish);
        View sideBarSidePadding = navDestinationSide.findViewById(R.id.viewPadding);
        View bottomBarSidePadding = navDestinationBottom.findViewById(R.id.viewPadding);

        sideBarSideTvPosition.setMaxLines(5);
        bottomBarSideTvPosition.setMaxLines(1);
        sideBarSideTvFinish.setMaxLines(5);
        bottomBarSideTvFinish.setMaxLines(2);
        sideBarSideTvDirection.setMaxLines(2);
        bottomBarSideTvDirection.setMaxLines(1);
        sideBarBottomIv.setVisibility(View.GONE);
        bottomBarSideIv.setVisibility(View.GONE);
        sideBarSidePadding.setVisibility(View.GONE);
        ivSideNavIcon.setVisibility(View.GONE);
        bottomBarSidePadding.setVisibility(View.VISIBLE);

        if (isInit == false) {
            isInit = true;
            startTheCar();
        }

        mOpenMapButton.setOnClickListener(btnView -> {
            mOpenMapButton.setEnabled(false);
            mOpenMapButton.setAlpha(0.5f);
            spinImage();
            navDestinationSide.setVisibility(View.GONE);
            navDestinationBottom.setVisibility(View.GONE);
            setUpStretchAction();
            mOpenMapButton.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mOpenMapButton.setEnabled(true);
                    mOpenMapButton.setAlpha(1f);
                    if (isMapOpened) {
                        mapsCard.setVisibility(View.VISIBLE);
                    }
                }
            }, 1500);
        });

        //Open the map when Booting up
        setUpStretchAction();
        mapsCard.setVisibility(View.VISIBLE);
    }

    private void startTheCar() {
    }

    private void setAppWidgetView() {
        mAppWidgetManager = AppWidgetManager.getInstance(getActivity().getApplicationContext());
        initView();
        setUpAssistantView();
        mHost = new AppWidgetHost(getActivity().getApplicationContext(), LauncherConstant.HOST_ID) {
            protected AppWidgetHostView onCreateView(Context context, int appWidgetId, AppWidgetProviderInfo appWidget) {
                return new MyAppWidgetView(appWidgetId);
            }
        };
//        mAddAppwidget2.setOnClickListener(this);
//        mAddAppwidget3.setOnClickListener(this);
        displayAppWidget();
        mHost.startListening();
        mIsStarted = true;
    }

    private void setUpStretchAction() {
        isMapOpened = !isMapOpened;
        if (isMapOpened) {
            glMapStart.setGuidelinePercent(0.3f);
            glMapBottom.setGuidelinePercent(0.9f);
            expandTo(clBackgroundIv, glCarBackground, 0.15f);
            expandTo(clMainLauncher, glWidgetEndMaps, 0.45f);
            glWidgetEndMaps.requestLayout();
            mOpenMapButton.setImageResource(R.drawable.btn_stretch_in_common);
            mMapShadow.setImageResource(R.drawable.img_launcher_widget_shadow);
            navDestinationSide.setVisibility(View.VISIBLE);
        } else {
            mapsCard.setVisibility(View.INVISIBLE);
            glMapStart.setGuidelinePercent(0f);
            glMapBottom.setGuidelinePercent(0.6f);
            expandTo(clBackgroundIv, glCarBackground, 0f);
            expandTo(clMainLauncher, glWidgetEndMaps, 0.22f);
            glWidgetEndMaps.requestLayout();
            mOpenMapButton.setImageResource(R.drawable.btn_stretch_out_common);
            mMapShadow.setImageResource(R.drawable.img_launcher_widget_shadow_nav_collapse);
            navDestinationBottom.setVisibility(View.VISIBLE);
        }
    }

    private void expandTo(ConstraintLayout cl, Guideline gl, float ratio) {
        ConstraintSet constraintSet = new ConstraintSet();
        constraintSet.clone(cl);
        constraintSet.setGuidelinePercent(gl.getId(), ratio);
        Transition transition = new AutoTransition(); // Or AutoTransition(), etc.
        transition.setDuration(500L);
        TransitionManager.beginDelayedTransition(cl, transition);
        constraintSet.applyTo(cl);
    }

    private void setUpAssistantView() {
        setApngDrawable(mAssistantImageView, "standby_voiceassistant.png", false);
//        setApngDrawable(mLoadingImageView, "apng_general_loading.png");
        mLoadingImageView.setVisibility(View.GONE);
        mAssistantQuoteTextView.setVisibility(View.VISIBLE);
        mGreetingTextView.setText("Hi, Driver!");
        prepareQuoteOrFacts();
//        mAssistantContainerView.setOnClickListener(view -> handleOnWakeAssistant());
    }

    private void handleOnWakeAssistant() {
        setApngDrawable(mAssistantImageView, "loading_faster.png", false);
        mAssistantContainerView.setClickable(false);
        mLoadingImageView.setVisibility(View.VISIBLE);
        mAssistantQuoteTextView.setVisibility(View.GONE);

        Intent intentStart = new Intent("com.fpt.action.ASSISTANT_WAKEUP");
        getActivity().getApplicationContext().sendBroadcast(intentStart);

        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mAssistantContainerView.setClickable(true);
                mLoadingImageView.setVisibility(View.GONE);
                mAssistantQuoteTextView.setVisibility(View.VISIBLE);
                prepareQuoteOrFacts();
                setApngDrawable(mAssistantImageView, "listening.png", false);
            }
        }, 5000);
    }

    private void prepareQuoteOrFacts() {
        String[] listQuote = {
                "Life is a journey, enjoy the drive.",
                "Happiness is a long open road.",
                "Keep calm and drive on.",
                "The road is yours, respect it.",
                "Fewer shortcuts, safer journeys.",
                "Driving is freedom on four wheels.",
                "Eyes on the road, always ahead.",
                "Enjoy the ride, not just the destination.",
                "A smooth drive is a safe drive.",
                "Speed thrills but kills.",
                "Your car reflects your personality.",
                "Every mile tells a story.",
                "Rules save lives on the road.",
                "Stay alert, accidents hurt.",
                "Better late than never.",
                "Drive slow, enjoy the scenery.",
                "Whenever you need me,\nI'm here"
        };
        Random rand = new Random();
        int randomIndex = rand.nextInt(listQuote.length);
        String newQuote = listQuote[randomIndex];
        showFactsOrQuotes(newQuote, 0);
    }

    private void showFactsOrQuotes(String fullText, int startAt) {
        if (startAt == 0) {
            handlerQuotes.removeCallbacks(mRunnableQuotes);
        }

        if (startAt < fullText.length()) {
            String text =  fullText.substring(0, startAt) + "|";
            mAssistantQuoteTextView.setText(text);
            int newIndex = startAt + 1;
            mRunnableQuotes = new Runnable() {
                @Override
                public void run() {
                    showFactsOrQuotes(fullText, newIndex);
                }
            };
            handlerQuotes.postDelayed(mRunnableQuotes, 50);
        } else if (startAt == fullText.length()) {
            mAssistantQuoteTextView.setText(fullText.substring(0, startAt));
        }
    }

    private void setApngDrawable(ImageView iv, String url, boolean freeze) {
        APNGDrawable apngDrawable = APNGDrawable.fromAsset(getActivity().getApplicationContext(), url);
        if (freeze) {
            apngDrawable.setLoopLimit(1);
        }
        iv.setImageDrawable(apngDrawable);
    }

    private void setUpDialog() {
        mSelectAppFullModeDialog = new SelectAppFullModeDialog(getActivity().getApplicationContext());
        mSelectAppFullModeDialog.setOwnerActivity(requireActivity());
        mSelectAreaDialog = new SelectAreaDialog(getActivity().getApplicationContext());
        mSelectAreaDialog.setOwnerActivity(requireActivity());
    }
    void discoverAppWidget(int requestCode, int area) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_PICK);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, mHost.allocateAppWidgetId());
        intent.putExtra(LauncherConstant.AREA, area);
        startActivityForResult(intent, requestCode);
    }
    private void displayAppWidget() {
        String appWidgets[] = getResources().getStringArray(R.array.app_widgets);
        if (appWidgets.length >= LauncherConstant.APP_WIDGET_COUNT) {
            ComponentName appComponent2 = ComponentName.unflattenFromString(appWidgets[0]);
            ComponentName appComponent3 = ComponentName.unflattenFromString(appWidgets[1]);
            isAddWidgetAppInArea(appComponent2, mAppWidgetArea2, mAddAppwidget2);
            isAddWidgetAppInArea(appComponent3, mAppWidgetArea3, mAddAppwidget3);
        }
    }
    /**
     * If app widget does not available in area, will be display icon add app widget
     *
     * @param componentName
     */
    private void isAddWidgetAppInArea(ComponentName componentName, AppWidgetContainerView appWidgetView, ImageView addAppwidget) {
        if (checkAppWidgetAvailable(componentName)) {
            setUpAppWidgetView(componentName, appWidgetView);
            addAppwidget.setVisibility(View.GONE);
        } else {
            addAppwidget.setVisibility(View.VISIBLE);
        }
    }
    @TargetApi(Build.VERSION_CODES.N)
    private boolean checkAppWidgetAvailable(ComponentName widgetComponent) {
        List<AppWidgetProviderInfo> infoList = mAppWidgetManager.getInstalledProviders();
        return infoList
                .stream()
                .anyMatch(widgetInfo -> widgetInfo.provider.getPackageName().equals(widgetComponent.getPackageName()));
    }
    private void initView() {
        mAppWidgetArea2 = (AppWidgetContainerView) getView().findViewById(R.id.area2);
        mAppWidgetArea3 = (AppWidgetContainerView) getView().findViewById(R.id.area3);
        mAddAppwidget2 = (ImageView) getView().findViewById(R.id.btn_add_app_widget2);
        mAddAppwidget3 = (ImageView) getView().findViewById(R.id.btn_add_app_widget3);
        mMediaWidgetContainerView = (LinearLayout) getView().findViewById(R.id.widgetContainerView);
        mWeatherWidgetContainerView = (LinearLayout) getView().findViewById(R.id.weatherWidgetContainerView);

        clBackgroundIv = getView().findViewById(R.id.cl_background_iv);
        clNavIcon = getView().findViewById(R.id.cl_navigation_icon);
        clMainLauncher = getView().findViewById(R.id.cl_main_launcher);
        clMapLayout = getView().findViewById(R.id.frame_map_layout);

        glCarBackground = getView().findViewById(R.id.guidelineCarBackground);
        glWidgetEndMaps = getView().findViewById(R.id.guidelineWidgetEndMaps);
        glMapStart = getView().findViewById(R.id.guidelineMapStartMoving);
        glMapBottom = getView().findViewById(R.id.guidelineMapBottomMoving);
        mOpenMapButton = (ImageView) getView().findViewById(R.id.btn_stretch);
        ivNaviSpinning = (ImageView) getView().findViewById(R.id.icon_navi_anim);
        mMapShadow = (ImageView) getView().findViewById(R.id.reflect_map);
        animationCarBackground = getView().findViewById(R.id.iv_car_static);

        navDestinationSide = getView().findViewById(R.id.navDestinationSide);
        navDestinationBottom = getView().findViewById(R.id.navDestinationBottom);

        mAssistantContainerView = (ConstraintLayout) getView().findViewById(R.id.ll_assistant);
        mAssistantImageView = (ImageView) getView().findViewById(R.id.iv_assistant);
        mLoadingImageView = (ImageView) getView().findViewById(R.id.iv_loading);
        mAssistantQuoteTextView = (TextView) getView().findViewById(R.id.tv_assistant_quote);
        mGreetingTextView = (TextView) getView().findViewById(R.id.tv_greeting);
    }

    private void spinImage() {
        ObjectAnimator animator = ObjectAnimator.ofFloat(ivNaviSpinning, "rotation", 0f, 360f);
        animator.setDuration(1400);
        animator.start();
    }

    private void setUpTaskView(ViewGroup parent) {
        // Modification date 29-07-2023 19:00PM
        mTaskViewManager = new TaskViewManager(getActivity().getApplicationContext(),
                new HandlerExecutor(requireActivity().getMainThreadHandler()));
        mTaskViewManager.createTaskView(taskView -> {
            taskView.setListener(requireActivity().getMainExecutor(), mTaskViewListener);
            parent.addView(taskView);
            mTaskView = taskView;
        });
        //END
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onStart() {
        super.onStart();
        TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackListener);
        if (isInit == true) {
            startTheCar();
            prepareQuoteOrFacts();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskStackListener);
    }

    private void setUpAppWidgetView(ComponentName componentName, AppWidgetContainerView containerView) {
        int appWidgetId = mHost.allocateAppWidgetId();
        mAppWidgetManager.bindAppWidgetId(appWidgetId, componentName, null);
        AppWidgetProviderInfo appWidget = mAppWidgetManager.getAppWidgetInfo(appWidgetId);
        inflateAppWidgetView(appWidgetId, appWidget, containerView);
    }
    private void startMapsInTaskView() {
        if (mTaskView == null || !mTaskViewReady) {
            if (DEBUG) Log.d(TAG, "Can't start Maps due to TaskView isn't ready.");
            return;
        }
        if (!mUserManager.isUserUnlocked()) {
            if (DEBUG) Log.d(TAG, "Can't start Maps due to the user isn't unlocked.");
            return;
        }
        // Don't start Maps when the display is off for ActivityVisibilityTests.
        if (requireActivity().getDisplay().getState() != Display.STATE_ON) {
            if (DEBUG) Log.d(TAG, "Can't start Maps due to the display is off");
            return;
        }
        try {
            Log.w(TAG, "start Activity");
            ActivityOptions options = ActivityOptions.makeCustomAnimation(getActivity().getApplicationContext(),
                    /* enterResId= */ 0, /* exitResId= */ 0);
            Intent mapIntent = IviLauncherUtils.getMapsIntent();
            Rect launchBounds = new Rect();
            mTaskView.getBoundsOnScreen(launchBounds);
            mTaskView.startActivity(
                    PendingIntent.getActivity(getActivity().getApplicationContext(), /* requestCode= */ 0,
                            mapIntent,
                            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT),
                    /* fillInIntent= */ null, options, launchBounds);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "Maps activity not found", e);
        }
    }
    private void initBroadcast() {
        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "onReceive action: " + action);
                switch (action) {
                    case ACTION_SHOW_SELECT_APP_FULL_MODE_DIALOG:
                        mSelectAppFullModeDialog.show();
                        break;
                    case ACTION_SHOW_SELECT_AREA_DIALOG:
                        mSelectAreaDialog.show();
                        break;
                    case ACTION_START_APPGRID_WITH_OPTION:
                        // startAppGridWithOption(context);
                        break;
                    case ACTION_START_MAPS:
                        // launchMapsActivity();
                        break;
                    case ACTION_START_DIALER:
                        launchDialerActivity();
                        break;
                    case ACTION_START_MEDIA:
                        launchMediaActivity();
                        break;
                    case ACTION_SHOW_ALL_APPS:
                        startAppGrid();
                    default:
                        break;
                }
            }
        };
        mLocalBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "onReceive action: " + action);
                if (action == "android.intent.action.BOOT_COMPLETED") {
                    // initWifiAP();
                    if (isAutoTimeZoneEnabled() && getDefaultTimeZone() != DEFAULT_TIME_ZONE) {
                        setDefaultTimeZone();
                    }
                }
            }
        };
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_SHOW_ALL_APPS);
        intentFilter.addAction(ACTION_SHOW_SELECT_APP_FULL_MODE_DIALOG);
        intentFilter.addAction(ACTION_SHOW_SELECT_AREA_DIALOG);
        intentFilter.addAction(ACTION_START_APPGRID_WITH_OPTION);
        intentFilter.addAction(ACTION_START_DIALER);
        intentFilter.addAction(ACTION_START_MAPS);
        intentFilter.addAction(ACTION_START_MEDIA);
        requireActivity().registerReceiver(mBroadcastReceiver, intentFilter);
        IntentFilter localFilter = new IntentFilter();
        localFilter.addAction("android.intent.action.BOOT_COMPLETED");
        LocalBroadcastManager.getInstance(getActivity().getApplicationContext()).registerReceiver(mLocalBroadcastReceiver, localFilter);
    }
    private void startAppGrid() {
        /*Intent intent = new Intent(getActivity().getApplicationContext(), AppGridActivity.class);
        startActivity(intent);*/
    }
    private void setDefaultTimeZone() {
        AlarmManager am = (AlarmManager) getActivity().getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        am.setTimeZone(DEFAULT_TIME_ZONE);
    }
    private String getDefaultTimeZone() {
        Calendar now = Calendar.getInstance();
        return now.getTimeZone().getID();
    }
    private boolean isAutoTimeZoneEnabled() {
        return Settings.Global.getInt(getActivity().getApplicationContext().getContentResolver(),
                Settings.Global.AUTO_TIME_ZONE, 0) > 0;
    }
    private void launchMapsActivity() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(requireActivity().getDisplay().getDisplayId());
        startActivity(IviLauncherUtils.getMapsIntent(), options.toBundle());
        Log.d(TAG, "start maps success ");
    }
    private void launchDialerActivity() {
        startActivity(IviLauncherUtils.getDialerIntent());
    }
    private void launchMediaActivity() {
        startActivity(IviLauncherUtils.getMediaIntent());
    }
    void configureAppWidget(int requestCode, int appWidgetId, ComponentName configure) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
        intent.setComponent(configure);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        SharedPreferences.Editor prefs = requireActivity().getPreferences(0).edit();
        prefs.putInt(PENDING_APPWIDGET_ID, appWidgetId);
        prefs.commit();
        startActivityForResult(intent, requestCode);
    }
    void handleAppWidgetPickResult(int resultCode, Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras != null) {
            int appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID);
            int area = extras.getInt(LauncherConstant.AREA);
            if (resultCode == requireActivity().RESULT_OK) {
                AppWidgetProviderInfo appWidget = mAppWidgetManager.getAppWidgetInfo(appWidgetId);
                if (appWidget.configure != null) {
                    configureAppWidget(CONFIGURE_APPWIDGET_REQUEST, appWidgetId, appWidget.configure);
                } else {
                    if (area == LauncherConstant.WIDGET_AREA2) {
                        inflateAppWidgetView(appWidgetId, appWidget, mAppWidgetArea2);
                        mAddAppwidget2.setVisibility(View.GONE);
                    }
                    if (area == LauncherConstant.WIDGET_AREA3) {
                        inflateAppWidgetView(appWidgetId, appWidget, mAppWidgetArea3);
                        mAddAppwidget3.setVisibility(View.GONE);
                    }
                }
            } else {
                mHost.deleteAppWidgetId(appWidgetId);
            }
        } else {
            Log.d(TAG, "extras: null");
        }
    }
    void handleAppWidgetConfigureResult(int resultCode, Intent intent) {
        Bundle extras = intent.getExtras();
        int appWidgetId = requireActivity().getPreferences(0).getInt(PENDING_APPWIDGET_ID, -1);
        if (appWidgetId < 0) {
            Log.w(TAG, "was no preference for PENDING_APPWIDGET_ID");
            return;
        }
        if (resultCode == requireActivity().RESULT_OK) {
            AppWidgetProviderInfo appWidget = mAppWidgetManager.getAppWidgetInfo(appWidgetId);
            if (extras != null) {
                int area = extras.getInt(LauncherConstant.AREA);
                if (area == LauncherConstant.WIDGET_AREA2) {
                    inflateAppWidgetView(appWidgetId, appWidget, mAppWidgetArea2);
                    mAddAppwidget2.setVisibility(View.GONE);
                }
                if (area == LauncherConstant.WIDGET_AREA3) {
                    inflateAppWidgetView(appWidgetId, appWidget, mAppWidgetArea3);
                    mAddAppwidget3.setVisibility(View.GONE);
                }
            } else {
                Log.d(TAG, "extras: null");
            }
        } else {
            mHost.deleteAppWidgetId(appWidgetId);
        }
    }
    void inflateAppWidgetView(int appWidgetId, AppWidgetProviderInfo appWidget, AppWidgetContainerView containerView) {
        // Inflate the AppWidget's RemoteViews
        AppWidgetHostView view = mHost.createView(getActivity().getApplicationContext(), appWidgetId, appWidget);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        containerView.addView(view, layoutParams);
        registerForContextMenu(view);
    }
    /*    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
            Log.d(TAG, "onActivityResult: ");
            switch (requestCode) {
                case DISCOVER_APPWIDGET_REQUEST:
                    Log.d(TAG, "DISCOVER_APPWIDGET_REQUEST: data" + data);
                    handleAppWidgetPickResult(resultCode, data);
                    break;
                case CONFIGURE_APPWIDGET_REQUEST:
                    Log.d(TAG, "CONFIGURE_APPWIDGET_REQUEST: data" + data);
                    handleAppWidgetConfigureResult(resultCode, data);
                    break;
            }
        }*/
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_add_app_widget2:
                discoverAppWidget(DISCOVER_APPWIDGET_REQUEST, LauncherConstant.WIDGET_AREA2);
                break;
            case R.id.btn_add_app_widget3:
                discoverAppWidget(DISCOVER_APPWIDGET_REQUEST, LauncherConstant.WIDGET_AREA3);
                break;
            default:
                Log.d(TAG, "App widget null:");
        }
    }
    class MyAppWidgetView extends AppWidgetHostView implements ContextMenu.ContextMenuInfo {
        int appWidgetId;
        MyAppWidgetView(int appWidgetId) {
            super(getActivity().getApplicationContext());
            this.appWidgetId = appWidgetId;
        }
        public ContextMenu.ContextMenuInfo getContextMenuInfo() {
            return this;
        }
    }
    AppWidgetHost mHost;
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "mMediaWidgetContainerView: " + mMediaWidgetContainerView);
        if (mMediaWidgetContainerView != null) {
            mMediaWidgetContainerView.setVisibility(View.VISIBLE);
        }
        if (mWeatherWidgetContainerView != null) {
            mWeatherWidgetContainerView.setVisibility(View.VISIBLE);
        }
    }
    private void initWifiAP() {
        Log.d(TAG, "initWifiAP() called");
        HandlerThread mHandlerThread = new HandlerThread("WIFI-AP-EVENT");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        this.mWifiManager = getActivity().getApplicationContext().getSystemService(WifiManager.class);
        this.mConnectivityManager = (ConnectivityManager) getActivity().getApplicationContext().getSystemService(
                Context.CONNECTIVITY_SERVICE);
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mWifiManager.setWifiEnabled(true);
                WifiConfiguration wifiConfiguration = new WifiConfiguration();
                wifiConfiguration.SSID = String.format("\"%s\"", LauncherConstant.WIFI_NAME_DEFAULT);
                wifiConfiguration.preSharedKey = String.format("\"%s\"", LauncherConstant.WIFI_PASS_DEFAULT);
                mWifiManager.disconnect();
                mWifiManager.enableNetwork(mWifiManager.addNetwork(wifiConfiguration), true);
                mWifiManager.reconnect();
            }
        });
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                WifiConfiguration wifiApConfiguration = mWifiManager.getWifiApConfiguration();
                wifiApConfiguration.SSID = LauncherConstant.WIFI_AP_NAME_DEFAULT;
                wifiApConfiguration.preSharedKey = LauncherConstant.WIFI_AP_PASS_DEFAULT;
                mWifiManager.setWifiApConfiguration(wifiApConfiguration);
                mWifiManager.setWifiEnabled(true);
                mConnectivityManager.startTethering(ConnectivityManager.TETHERING_WIFI,
                        false, mOnStartTetheringCallback);
            }
        }, 100);
    }
    private final ConnectivityManager.OnStartTetheringCallback mOnStartTetheringCallback =
            new ConnectivityManager.OnStartTetheringCallback() {
                @Override
                public void onTetheringFailed() {
                    super.onTetheringFailed();
                }
                @Override
                public void onTetheringStarted() {
                    super.onTetheringStarted();
                }
            };
    // /** Brings the Car Launcher to the foreground. */
    private void bringToForeground() {
        Log.d(TAG, "bringToForeground mCarLauncherTaskId=" + mCarLauncherTaskId);
        if (mCarLauncherTaskId != INVALID_TASK_ID) {
            mActivityManager.moveTaskToFront(mCarLauncherTaskId,  /* flags= */ 0);
        }
    }
    private boolean isPackageRunning(Context context, String packageName) {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            for (ActivityManager.RunningAppProcessInfo processInfo : activityManager.getRunningAppProcesses()) {
                if (processInfo.processName.equals(packageName) &&
                        processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    return true;
                }
            }
        }
        return false;
    }
    @Override
    public void onDestroy() {
        Log.w(TAG, "onDestroy");
        requireActivity().unregisterReceiver(mBroadcastReceiver);
        if (mIsStarted) {
            mHost.stopListening();
            mIsStarted = false;
        }
        release();
        super.onDestroy();
    }
}