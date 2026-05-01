/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.policy;

import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;

import android.app.ActivityOptions;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.text.TextUtils;
import android.util.MathUtils;
import android.util.Slog;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.internal.display.BrightnessUtils;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import java.util.ArrayList;
import java.util.List;

final class QuickSettingsOverlay {
    private static final String TAG = "QuickSettingsOverlay";

    private static final int MODE_NOTIFICATIONS = 0;
    private static final int MODE_QUICK_SETTINGS = 1;

    private static final int ROW_FIRST_BUTTONS = 0;
    private static final int ROW_SECOND_BUTTONS = 1;
    private static final int BUTTON_ROW_COUNT = 2;
    private static final int ROW_BRIGHTNESS = BUTTON_ROW_COUNT;
    private static final int FIRST_VOLUME_ROW = ROW_BRIGHTNESS + 1;
    private static final int VOLUME_COUNT = 4;
    private static final int LAST_ROW = FIRST_VOLUME_ROW + VOLUME_COUNT - 1;
    private static final int BRIGHTNESS_STEPS = 10;
    private static final int VOLUME_ROW_HEIGHT_DP = 56;

    private static final int BUTTON_AIRPLANE = 0;
    private static final int BUTTON_RINGER = 1;
    private static final int BUTTON_DND = 2;
    private static final int BUTTON_HOTSPOT = 3;
    private static final int BUTTON_BATTERY_SAVER = 4;
    private static final int BUTTON_TOUCHSCREEN = 5;
    private static final int BUTTON_MOBILE_DATA = 6;
    private static final int BUTTON_FLASHLIGHT = 7;
    private static final int BUTTONS_PER_ROW = 4;
    private static final int BUTTON_COUNT = BUTTON_ROW_COUNT * BUTTONS_PER_ROW;
    private static final int NOTIFICATION_DISMISS_ANIMATION_MS = 320;
    private static final int NOTIFICATION_DISMISS_DISTANCE_DP = 120;
    private static final int NOTIFICATION_SWIPE_THRESHOLD_DP = 48;
    private static final int OVERLAY_DISMISS_SWIPE_THRESHOLD_DP = 64;
    private static final String[] BUTTON_LABELS = {
            "Airplane Mode",
            "Ringer Mode",
            "Do Not Disturb",
            "Wi-Fi Hotspot",
            "Battery Saver",
            "Touchscreen",
            "Mobile Data",
            "Flashlight",
    };

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final AudioManager mAudioManager;
    private final ConnectivityManager mConnectivityManager;
    private final DisplayManager mDisplayManager;
    private final NotificationManager mNotificationManager;
    private final INotificationManager mNotificationService;
    private final IStatusBarService mStatusBarService;
    private final PowerManager mPowerManager;
    private final TelephonyManager mTelephonyManager;
    private final CameraManager mCameraManager;
    private final InputManager mInputManager;
    private final boolean mAutomaticBrightnessAvailable;
    private final LinearLayout mRoot;
    private final TextView mCaption;
    private final ScrollView mScrollView;
    private final LinearLayout mScrollableContent;
    private final LinearLayout mQuickSettingsContainer;
    private final LinearLayout mNotificationsContainer;
    private final LinearLayout[] mButtonRows = new LinearLayout[BUTTON_ROW_COUNT];
    private final LinearLayout[] mButtons = new LinearLayout[BUTTON_COUNT];
    private final TextView[] mButtonStates = new TextView[BUTTON_COUNT];
    private final BrightnessControl mBrightnessControl;
    private final VolumeControl[] mVolumeControls = new VolumeControl[VOLUME_COUNT];
    private final List<NotificationItem> mNotificationItems = new ArrayList<>();
    private final List<ActionMenuItem> mActionMenuItems = new ArrayList<>();
    private final List<Integer> mTouchscreenDeviceIds = new ArrayList<>();

    private int mMode = MODE_NOTIFICATIONS;
    private int mFocusedRow = ROW_FIRST_BUTTONS;
    private int mFocusedButton;
    private int mFocusedNotification;
    private float mOverlayTouchDownX;
    private float mOverlayTouchDownY;
    private boolean mNotificationDismissInProgress;
    private boolean mShowingNotificationActionMenu;
    private boolean mShowingRemoteInputEditor;
    private boolean mWifiHotspotEnabled;
    private boolean mTouchscreenEnabled = true;
    private boolean mFlashlightEnabled;
    private int mFocusedActionMenuItem;
    private NotificationItem mActionMenuSourceItem;
    private NotificationItem mRemoteInputSourceItem;
    private ActionMenuItem mRemoteInputActionItem;
    private RemoteInput mRemoteInput;
    private LinearLayout mRemoteInputEditor;
    private EditText mRemoteInputEditText;
    private String mRearFlashCameraId;
    private volatile boolean mShowing;

    QuickSettingsOverlay(Context context) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
        mAudioManager = context.getSystemService(AudioManager.class);
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mNotificationService = NotificationManager.getService();
        mStatusBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mPowerManager = context.getSystemService(PowerManager.class);
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mCameraManager = context.getSystemService(CameraManager.class);
        mInputManager = context.getSystemService(InputManager.class);
        mAutomaticBrightnessAvailable = context.getResources().getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);

        mRoot = new LinearLayout(context) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                if (handleOverlaySwipe(event)) {
                    return true;
                }
                return super.dispatchTouchEvent(event);
            }
        };
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setGravity(Gravity.CENTER_HORIZONTAL);
        mRoot.setFocusable(true);
        mRoot.setFocusableInTouchMode(true);
        mRoot.setPadding(dp(16), dp(16), dp(16), dp(16));
        mRoot.setBackground(makeBackground(0xcc101418, 0, dp(18)));
        mRoot.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
                return true;
            }
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    handleUpDown(false);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    handleUpDown(true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    handleLeftRight(false);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    handleLeftRight(true);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    activateFocusedItem();
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    toggleMode();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (mShowingRemoteInputEditor) {
                        closeRemoteInputEditor(true);
                    } else if (mShowingNotificationActionMenu) {
                        closeNotificationActionMenu();
                    } else {
                        hide();
                    }
                    return true;
            }
            return false;
        });

        mCaption = new TextView(context);
        mCaption.setTextColor(Color.WHITE);
        mCaption.setTextSize(18);
        mCaption.setGravity(Gravity.CENTER);
        mRoot.addView(mCaption, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(32)));

        mScrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxHeight = getMaxBodyHeight();
                int cappedHeightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, cappedHeightSpec);
            }
        };
        mScrollView.setFillViewport(true);
        mRoot.addView(mScrollView, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT));

        mScrollableContent = new LinearLayout(context);
        mScrollableContent.setOrientation(LinearLayout.VERTICAL);
        mScrollView.addView(mScrollableContent, new FrameLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT));

        mQuickSettingsContainer = new LinearLayout(context);
        mQuickSettingsContainer.setOrientation(LinearLayout.VERTICAL);
        mScrollableContent.addView(mQuickSettingsContainer, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT));

        mNotificationsContainer = new LinearLayout(context);
        mNotificationsContainer.setOrientation(LinearLayout.VERTICAL);
        mScrollableContent.addView(mNotificationsContainer, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT));

        LinearLayout firstButtonRow = addButtonRow(mQuickSettingsContainer);
        mButtonRows[ROW_FIRST_BUTTONS] = firstButtonRow;
        addButton(firstButtonRow, BUTTON_AIRPLANE,
                com.android.internal.R.drawable.ic_lock_airplane_mode);
        addButton(firstButtonRow, BUTTON_RINGER,
                com.android.internal.R.drawable.ic_volume);
        addButton(firstButtonRow, BUTTON_DND,
                com.android.internal.R.drawable.ic_qs_dnd);
        addButton(firstButtonRow, BUTTON_HOTSPOT,
                com.android.internal.R.drawable.ic_hotspot_transient_animation);

        LinearLayout secondButtonRow = addButtonRow(mQuickSettingsContainer);
        mButtonRows[ROW_SECOND_BUTTONS] = secondButtonRow;
        addButton(secondButtonRow, BUTTON_BATTERY_SAVER,
                com.android.internal.R.drawable.ic_qs_battery_saver);
        addButton(secondButtonRow, BUTTON_TOUCHSCREEN,
                com.android.internal.R.drawable.ic_accessibility_one_handed);
        addButton(secondButtonRow, BUTTON_MOBILE_DATA,
                com.android.internal.R.drawable.ic_menu);
        addButton(secondButtonRow, BUTTON_FLASHLIGHT,
                com.android.internal.R.drawable.ic_qs_flashlight);

        mBrightnessControl = addBrightnessControl();
        addVolumeControl(0, "Call Volume", AudioManager.STREAM_VOICE_CALL, 0xff35a7ff);
        addVolumeControl(1, "Media Volume", AudioManager.STREAM_MUSIC, 0xff30d158);
        addVolumeControl(2, "Notification Volume", AudioManager.STREAM_NOTIFICATION, 0xffffb020);
        addVolumeControl(3, "Alarm Volume", AudioManager.STREAM_ALARM, 0xffff5f57);

        updateState();
    }

    void handleMenuPressed() {
        if (mShowing) {
            toggleMode();
        } else {
            show(MODE_NOTIFICATIONS);
        }
    }

    void toggle() {
        handleMenuPressed();
    }

    boolean isShowing() {
        return mShowing;
    }

    void show(int mode) {
        if (mShowing || mWindowManager == null) {
            setMode(mode);
            return;
        }

        setMode(mode);
        updateState();
        WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                TYPE_KEYGUARD_DIALOG,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        attrs.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        attrs.y = getOverlayTopOffset();
        attrs.dimAmount = 0.35f;
        attrs.setTitle("DumbDroidQuickSettingsOverlay");

        try {
            mWindowManager.addView(mRoot, attrs);
            mShowing = true;
            mRoot.requestFocus();
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to show quick settings overlay", e);
        }
    }

    void hide() {
        if (!mShowing || mWindowManager == null) {
            return;
        }
        try {
            mWindowManager.removeView(mRoot);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to hide quick settings overlay", e);
        } finally {
            mShowing = false;
        }
    }

    private void toggleMode() {
        resetNotificationOverlayState();
        setMode(mMode == MODE_NOTIFICATIONS ? MODE_QUICK_SETTINGS : MODE_NOTIFICATIONS);
    }

    private boolean handleOverlaySwipe(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mOverlayTouchDownX = event.getX();
                mOverlayTouchDownY = event.getY();
                return false;
            case MotionEvent.ACTION_UP:
                float deltaX = event.getX() - mOverlayTouchDownX;
                float deltaY = event.getY() - mOverlayTouchDownY;
                if (deltaY <= -dp(OVERLAY_DISMISS_SWIPE_THRESHOLD_DP)
                        && Math.abs(deltaY) > Math.abs(deltaX)) {
                    if (mMode == MODE_QUICK_SETTINGS) {
                        setMode(MODE_NOTIFICATIONS);
                    } else {
                        hide();
                    }
                    return true;
                }
                if (mMode == MODE_NOTIFICATIONS
                        && deltaY >= dp(OVERLAY_DISMISS_SWIPE_THRESHOLD_DP)
                        && Math.abs(deltaY) > Math.abs(deltaX)) {
                    setMode(MODE_QUICK_SETTINGS);
                    return true;
                }
                return false;
            case MotionEvent.ACTION_CANCEL:
                return false;
        }
        return false;
    }

    private void setMode(int mode) {
        mMode = mode;
        resetNotificationOverlayState();
        if (mMode == MODE_NOTIFICATIONS) {
            refreshNotifications();
        } else {
            updateState();
        }
        mQuickSettingsContainer.setVisibility(mMode == MODE_QUICK_SETTINGS ? View.VISIBLE : View.GONE);
        mNotificationsContainer.setVisibility(mMode == MODE_NOTIFICATIONS ? View.VISIBLE : View.GONE);
        mScrollView.scrollTo(0, 0);
        updateFocus();
    }

    private LinearLayout addButtonRow(LinearLayout container) {
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(70));
        params.topMargin = dp(8);
        container.addView(row, params);
        return row;
    }

    private void addButton(LinearLayout parent, int index, int iconRes) {
        LinearLayout button = new LinearLayout(mContext);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6), dp(8), dp(6), dp(8));
        button.setClickable(true);
        button.setOnClickListener(view -> {
            mFocusedRow = index / BUTTONS_PER_ROW;
            mFocusedButton = index % BUTTONS_PER_ROW;
            activateButton(index);
        });

        ImageView icon = new ImageView(mContext);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Color.WHITE);
        button.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView state = new TextView(mContext);
        state.setTextColor(Color.WHITE);
        state.setTextSize(11);
        state.setGravity(Gravity.CENTER);
        button.addView(state, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(20)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                WindowManager.LayoutParams.MATCH_PARENT, 1f);
        if (index % BUTTONS_PER_ROW > 0) {
            params.leftMargin = dp(8);
        }
        parent.addView(button, params);

        mButtons[index] = button;
        mButtonStates[index] = state;
    }

    private BrightnessControl addBrightnessControl() {
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.setClickable(true);
        row.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                int width = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
                setBrightnessFromTouch(event.getX() - view.getPaddingLeft(), width);
            }
            return true;
        });

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(VOLUME_ROW_HEIGHT_DP));
        rowParams.topMargin = dp(8);
        mQuickSettingsContainer.addView(row, rowParams);

        FrameLayout track = new FrameLayout(mContext);
        track.setBackground(makeBackground(0xff151d24, 0, dp(8)));
        track.setClickable(true);
        track.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                setBrightnessFromTouch(event.getX(), view.getWidth());
            }
            return true;
        });
        row.addView(track, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(16)));

        LinearLayout fillContainer = new LinearLayout(mContext);
        fillContainer.setOrientation(LinearLayout.HORIZONTAL);
        track.addView(fillContainer, new FrameLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT));

        View fill = new View(mContext);
        fill.setBackground(makeBackground(0xfff4d35e, 0, dp(8)));
        fillContainer.addView(fill, new LinearLayout.LayoutParams(0,
                WindowManager.LayoutParams.MATCH_PARENT, 0f));

        View empty = new View(mContext);
        fillContainer.addView(empty, new LinearLayout.LayoutParams(0,
                WindowManager.LayoutParams.MATCH_PARENT, 1f));

        TextView autoLabel = new TextView(mContext);
        autoLabel.setText("auto");
        autoLabel.setTextColor(Color.WHITE);
        autoLabel.setTextSize(11);
        autoLabel.setGravity(Gravity.CENTER);
        autoLabel.setVisibility(View.GONE);
        track.addView(autoLabel, new FrameLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        return new BrightnessControl(row, fillContainer, fill, empty, autoLabel);
    }

    private void addVolumeControl(int index, String label, int stream, int color) {
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.setClickable(true);
        row.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                int width = view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
                setVolumeFromTouch(index, event.getX() - view.getPaddingLeft(), width);
            }
            return true;
        });

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(VOLUME_ROW_HEIGHT_DP));
        rowParams.topMargin = dp(8);
        mQuickSettingsContainer.addView(row, rowParams);

        LinearLayout track = new LinearLayout(mContext);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackground(makeBackground(0xff151d24, 0, dp(8)));
        track.setClickable(true);
        track.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                setVolumeFromTouch(index, event.getX(), view.getWidth());
            }
            return true;
        });
        row.addView(track, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(16)));

        View fill = new View(mContext);
        fill.setBackground(makeBackground(color, 0, dp(8)));
        track.addView(fill, new LinearLayout.LayoutParams(0,
                WindowManager.LayoutParams.MATCH_PARENT, 0f));

        View empty = new View(mContext);
        track.addView(empty, new LinearLayout.LayoutParams(0,
                WindowManager.LayoutParams.MATCH_PARENT, 1f));

        mVolumeControls[index] = new VolumeControl(label, stream, row, fill, empty);
    }

    private void handleUpDown(boolean down) {
        if (mMode == MODE_NOTIFICATIONS) {
            if (mShowingRemoteInputEditor) {
                return;
            }
            if (mShowingNotificationActionMenu) {
                if (mActionMenuItems.isEmpty()) {
                    return;
                }
                mFocusedActionMenuItem = MathUtils.constrain(
                        mFocusedActionMenuItem + (down ? 1 : -1),
                        0, mActionMenuItems.size() - 1);
                updateFocus();
                return;
            }
            if (mNotificationItems.isEmpty() || mNotificationDismissInProgress) {
                return;
            }
            mFocusedNotification = MathUtils.constrain(mFocusedNotification + (down ? 1 : -1),
                    0, mNotificationItems.size() - 1);
            updateFocus();
            return;
        }
        setFocusedRow(mFocusedRow + (down ? 1 : -1));
    }

    private void handleLeftRight(boolean right) {
        if (mMode == MODE_NOTIFICATIONS) {
            if (mShowingNotificationActionMenu || mShowingRemoteInputEditor) {
                return;
            }
            dismissFocusedNotification(right, true);
            return;
        }
        handleQuickSettingsLeftRight(right);
    }

    private void handleQuickSettingsLeftRight(boolean right) {
        if (isBrightnessRowFocused()) {
            changeBrightness(right);
            return;
        }
        if (isVolumeRowFocused()) {
            changeFocusedVolume(right);
            return;
        }

        mFocusedButton = MathUtils.constrain(mFocusedButton + (right ? 1 : -1),
                0, BUTTONS_PER_ROW - 1);
        updateFocus();
    }

    private void activateFocusedItem() {
        if (mMode == MODE_NOTIFICATIONS) {
            if (mShowingRemoteInputEditor) {
                submitRemoteInput();
                return;
            }
            if (mShowingNotificationActionMenu) {
                activateFocusedActionMenuItem();
                return;
            }
            activateFocusedNotification();
        } else if (isBrightnessRowFocused()) {
            toggleAutoBrightness();
        } else {
            activateFocusedButton();
        }
    }

    private void refreshNotifications() {
        mNotificationItems.clear();
        mNotificationsContainer.removeAllViews();

        StatusBarNotification[] notifications = getActiveNotifications();
        for (StatusBarNotification sbn : notifications) {
            if (!shouldShowNotification(sbn)) {
                continue;
            }
            NotificationItem item = createNotificationItem(sbn);
            mNotificationItems.add(item);
            mNotificationsContainer.addView(item.row, new LinearLayout.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT));
        }

        if (mNotificationItems.isEmpty()) {
            TextView empty = new TextView(mContext);
            empty.setText("No notifications");
            empty.setTextColor(Color.WHITE);
            empty.setTextSize(16);
            empty.setGravity(Gravity.CENTER);
            mNotificationsContainer.addView(empty, new LinearLayout.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, dp(72)));
            mFocusedNotification = 0;
            return;
        }

        mFocusedNotification = MathUtils.constrain(mFocusedNotification, 0,
                mNotificationItems.size() - 1);
    }

    private boolean shouldShowNotification(StatusBarNotification sbn) {
        return !sbn.isAppOrSystemGroupSummary();
    }

    private Notification.Action[] getVisibleNotificationActions(StatusBarNotification sbn) {
        Notification.Action[] actions = sbn.getNotification().actions;
        if (actions == null || actions.length == 0) {
            return new Notification.Action[0];
        }

        List<Notification.Action> visibleActions = new ArrayList<>();
        for (Notification.Action action : actions) {
            if (action == null || action.actionIntent == null || TextUtils.isEmpty(action.title)) {
                continue;
            }
            visibleActions.add(action);
        }
        return visibleActions.toArray(new Notification.Action[0]);
    }

    private String getNotificationActionSummary(StatusBarNotification sbn) {
        Notification.Action[] actions = getVisibleNotificationActions(sbn);
        if (actions.length == 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        int displayed = Math.min(actions.length, 3);
        for (int i = 0; i < displayed; i++) {
            if (i > 0) {
                builder.append(" | ");
            }
            builder.append(actions[i].title);
        }
        if (actions.length > displayed) {
            builder.append(" +").append(actions.length - displayed);
        }
        return builder.toString();
    }

    private void showNotificationActionMenu(NotificationItem item) {
        showNotificationActionMenu(item, 0);
    }

    private void showNotificationActionMenu(NotificationItem item, int focusedIndex) {
        clearRemoteInputEditorState();
        mShowingNotificationActionMenu = true;
        mActionMenuSourceItem = item;
        mFocusedActionMenuItem = focusedIndex;
        mActionMenuItems.clear();
        mNotificationsContainer.removeAllViews();

        addActionMenuItem("Open", null, -1);
        Notification.Action[] actions = getVisibleNotificationActions(item.sbn);
        for (int i = 0; i < actions.length; i++) {
            addActionMenuItem(actions[i].title.toString(), actions[i], i);
        }
        mFocusedActionMenuItem = MathUtils.constrain(mFocusedActionMenuItem, 0,
                Math.max(0, mActionMenuItems.size() - 1));
        mRoot.requestFocus();
        updateFocus();
    }

    private void closeNotificationActionMenu() {
        clearRemoteInputEditorState();
        mShowingNotificationActionMenu = false;
        mActionMenuSourceItem = null;
        mActionMenuItems.clear();
        refreshNotifications();
        mRoot.requestFocus();
        updateFocus();
    }

    private void addActionMenuItem(String label, Notification.Action action, int actionIndex) {
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setMinimumHeight(dp(56));
        row.setClickable(true);

        TextView title = new TextView(mContext);
        title.setText(label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        row.addView(title, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT));

        if (action != null && getTextRemoteInput(action) != null) {
            TextView detail = new TextView(mContext);
            detail.setText("Requires input");
            detail.setTextColor(0xffc7d0d9);
            detail.setTextSize(11);
            row.addView(detail, new LinearLayout.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT));
        }

        ActionMenuItem item = new ActionMenuItem(label, action, actionIndex, row);
        row.setOnClickListener(view -> {
            int index = mActionMenuItems.indexOf(item);
            if (index >= 0) {
                mFocusedActionMenuItem = index;
                activateFocusedActionMenuItem();
            }
        });
        mActionMenuItems.add(item);
        mNotificationsContainer.addView(row, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT));
    }

    private boolean hasActionRemoteInput(Notification.Action action) {
        return action != null && action.getRemoteInputs() != null
                && action.getRemoteInputs().length > 0;
    }

    private RemoteInput getTextRemoteInput(Notification.Action action) {
        if (!hasActionRemoteInput(action)) {
            return null;
        }
        RemoteInput fallback = null;
        for (RemoteInput remoteInput : action.getRemoteInputs()) {
            if (remoteInput == null) {
                continue;
            }
            if (remoteInput.getAllowFreeFormInput()) {
                return remoteInput;
            }
            if (fallback == null) {
                fallback = remoteInput;
            }
        }
        return fallback;
    }

    private void dismissStatusBarNotification(StatusBarNotification sbn, int rank, int count)
            throws RemoteException {
        NotificationVisibility visibility = NotificationVisibility.obtain(
                sbn.getKey(),
                MathUtils.constrain(rank, 0, Math.max(0, count - 1)),
                count,
                true,
                NotificationVisibility.NotificationLocation.LOCATION_MAIN_AREA);
        mStatusBarService.onNotificationClear(
                sbn.getPackageName(),
                sbn.getUserId(),
                sbn.getKey(),
                NotificationStats.DISMISSAL_SHADE,
                NotificationStats.DISMISS_SENTIMENT_NEUTRAL,
                visibility);
    }

    private StatusBarNotification getDismissableSummaryFor(NotificationItem item,
            StatusBarNotification[] activeNotifications) {
        StatusBarNotification sbn = item.sbn;
        if (!sbn.isGroup() || sbn.getNotification().isGroupSummary()) {
            return null;
        }

        StatusBarNotification summary = null;
        int childCount = 0;
        for (StatusBarNotification candidate : activeNotifications) {
            if (!sbn.getGroupKey().equals(candidate.getGroupKey())
                    || candidate.getUserId() != sbn.getUserId()) {
                continue;
            }
            if (candidate.isAppOrSystemGroupSummary()) {
                summary = candidate;
            } else {
                childCount++;
            }
        }
        return childCount == 1 && summary != null && summary.isClearable() ? summary : null;
    }

    private void scheduleNotificationOverlayRefresh() {
        mRoot.postDelayed(() -> {
            if (!mShowing || mMode != MODE_NOTIFICATIONS) {
                return;
            }
            refreshNotifications();
            updateFocus();
        }, NOTIFICATION_DISMISS_ANIMATION_MS);
    }

    private StatusBarNotification[] getActiveNotifications() {
        if (mNotificationService == null) {
            return new StatusBarNotification[0];
        }
        try {
            return mNotificationService.getActiveNotificationsWithAttribution(
                    mContext.getPackageName(), mContext.getAttributionTag());
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to fetch active notifications", e);
        } catch (SecurityException e) {
            Slog.w(TAG, "Missing permission to fetch active notifications", e);
        }
        return new StatusBarNotification[0];
    }

    private NotificationItem createNotificationItem(StatusBarNotification sbn) {
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        ImageView icon = new ImageView(mContext);
        Notification notification = sbn.getNotification();
        if (notification.getSmallIcon() != null) {
            icon.setImageIcon(notification.getSmallIcon());
        } else {
            icon.setImageResource(com.android.internal.R.drawable.ic_menu);
        }
        icon.setColorFilter(Color.WHITE);
        row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));

        LinearLayout textColumn = new LinearLayout(mContext);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                WindowManager.LayoutParams.MATCH_PARENT, 1f);
        textParams.leftMargin = dp(12);
        row.addView(textColumn, textParams);

        TextView title = new TextView(mContext);
        title.setText(getNotificationTitle(sbn));
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setSingleLine(true);
        textColumn.addView(title, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(28)));

        TextView description = new TextView(mContext);
        description.setText(getNotificationDescription(notification));
        description.setTextColor(0xffc7d0d9);
        description.setTextSize(12);
        description.setSingleLine(true);
        description.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(description, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(24)));

        boolean hasProgress = hasNotificationProgress(notification);
        if (hasProgress) {
            addNotificationProgressBar(textColumn, notification);
        }

        String actionSummary = getNotificationActionSummary(sbn);
        int minimumHeightDp = 76;
        if (hasProgress) {
            minimumHeightDp += 16;
        }
        if (!actionSummary.isEmpty()) {
            minimumHeightDp += 16;
        }
        row.setMinimumHeight(dp(minimumHeightDp));
        if (!actionSummary.isEmpty()) {
            TextView actions = new TextView(mContext);
            actions.setText(actionSummary);
            actions.setTextColor(0xff8fd0ff);
            actions.setTextSize(11);
            actions.setSingleLine(true);
            actions.setEllipsize(TextUtils.TruncateAt.END);
            textColumn.addView(actions, new LinearLayout.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, dp(22)));
        }

        NotificationItem item = new NotificationItem(sbn, row);
        addNotificationTouchHandler(item);
        return item;
    }

    private void addNotificationTouchHandler(NotificationItem item) {
        final float[] downX = new float[1];
        final float[] downY = new float[1];

        item.row.setClickable(true);
        item.row.setOnTouchListener((view, event) -> {
            if (mNotificationDismissInProgress) {
                return true;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0] = event.getX();
                    downY[0] = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    float deltaX = event.getX() - downX[0];
                    float deltaY = event.getY() - downY[0];
                    if (Math.abs(deltaX) >= dp(NOTIFICATION_SWIPE_THRESHOLD_DP)
                            && Math.abs(deltaX) > Math.abs(deltaY)) {
                        if (!focusNotificationItem(item)) {
                            return true;
                        }
                        dismissFocusedNotification(deltaX > 0, true);
                    } else if (Math.abs(deltaY) < dp(OVERLAY_DISMISS_SWIPE_THRESHOLD_DP)) {
                        if (!focusNotificationItem(item)) {
                            return true;
                        }
                        activateFocusedNotification();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return true;
        });
    }

    private boolean focusNotificationItem(NotificationItem item) {
        int index = mNotificationItems.indexOf(item);
        if (index < 0) {
            return false;
        }
        mFocusedNotification = index;
        updateFocus();
        return true;
    }

    private String getNotificationTitle(StatusBarNotification sbn) {
        CharSequence title = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
        if (title != null && title.length() > 0) {
            return title.toString();
        }
        try {
            PackageManager packageManager = mContext.getPackageManager();
            return packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(sbn.getPackageName(), 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return sbn.getPackageName();
        }
    }

    private String getNotificationDescription(Notification notification) {
        CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (text == null || text.length() == 0) {
            text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        }
        return text != null ? text.toString() : "";
    }

    private boolean hasNotificationProgress(Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) {
            return false;
        }
        if (extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) {
            return true;
        }
        return extras.containsKey(Notification.EXTRA_PROGRESS_MAX)
                && extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0;
    }

    private void addNotificationProgressBar(LinearLayout parent, Notification notification) {
        Bundle extras = notification.extras;
        boolean indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
        int max = Math.max(0, extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0));
        int progress = MathUtils.constrain(extras.getInt(Notification.EXTRA_PROGRESS, 0), 0, max);
        float fraction = indeterminate
                ? 0.45f
                : MathUtils.constrain(progress / (float) Math.max(1, max), 0f, 1f);

        LinearLayout track = new LinearLayout(mContext);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setGravity(Gravity.CENTER_VERTICAL);
        track.setBackground(makeBackground(0xff151d24, 0, dp(6)));
        track.setAlpha(indeterminate ? 0.8f : 1f);
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(8));
        trackParams.topMargin = dp(6);
        parent.addView(track, trackParams);

        View fill = new View(mContext);
        fill.setBackground(makeBackground(indeterminate ? 0xff7aa7c7 : 0xff35a7ff, 0, dp(6)));
        track.addView(fill, new LinearLayout.LayoutParams(0,
                WindowManager.LayoutParams.MATCH_PARENT, fraction));

        View empty = new View(mContext);
        track.addView(empty, new LinearLayout.LayoutParams(0,
                WindowManager.LayoutParams.MATCH_PARENT, 1f - fraction));
    }

    private void dismissFocusedNotification(boolean right, boolean animate) {
        if (mNotificationItems.isEmpty() || mNotificationDismissInProgress) {
            return;
        }
        if (mStatusBarService == null) {
            return;
        }
        NotificationItem item = mNotificationItems.get(mFocusedNotification);
        StatusBarNotification[] activeNotifications = getActiveNotifications();
        StatusBarNotification summaryToDismiss = getDismissableSummaryFor(item, activeNotifications);
        try {
            dismissStatusBarNotification(item.sbn, mFocusedNotification, mNotificationItems.size());
            if (summaryToDismiss != null) {
                dismissStatusBarNotification(summaryToDismiss, mFocusedNotification,
                        mNotificationItems.size());
            }
        } catch (RemoteException | RuntimeException e) {
            Slog.w(TAG, "Unable to dismiss notification", e);
            return;
        }
        if (animate) {
            animateFocusedNotificationDismiss(right);
        } else {
            removeFocusedNotificationFromOverlay(item);
            scheduleNotificationOverlayRefresh();
            updateFocus();
        }
    }

    private void animateFocusedNotificationDismiss(boolean right) {
        mNotificationDismissInProgress = true;
        NotificationItem item = mNotificationItems.get(mFocusedNotification);
        item.row.animate()
                .translationX((right ? 1 : -1) * dp(NOTIFICATION_DISMISS_DISTANCE_DP))
                .alpha(0f)
                .setDuration(NOTIFICATION_DISMISS_ANIMATION_MS)
                .withEndAction(() -> {
                    removeFocusedNotificationFromOverlay(item);
                    mNotificationDismissInProgress = false;
                    scheduleNotificationOverlayRefresh();
                    updateFocus();
                })
                .start();
    }

    private void removeFocusedNotificationFromOverlay(NotificationItem item) {
        int removedIndex = mNotificationItems.indexOf(item);
        if (removedIndex < 0) {
            return;
        }
        mNotificationItems.remove(removedIndex);
        mNotificationsContainer.removeViewAt(removedIndex);
        if (!mNotificationItems.isEmpty()) {
            mFocusedNotification = MathUtils.constrain(removedIndex, 0,
                    mNotificationItems.size() - 1);
            return;
        }

        TextView empty = new TextView(mContext);
        empty.setText("No notifications");
        empty.setTextColor(Color.WHITE);
        empty.setTextSize(16);
        empty.setGravity(Gravity.CENTER);
        mNotificationsContainer.addView(empty, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(72)));
        mFocusedNotification = 0;
    }

    private void activateFocusedNotification() {
        if (mNotificationItems.isEmpty()) {
            return;
        }
        NotificationItem item = mNotificationItems.get(mFocusedNotification);
        if (getVisibleNotificationActions(item.sbn).length > 0) {
            showNotificationActionMenu(item);
            return;
        }
        openNotificationItem(item);
    }

    private void activateFocusedActionMenuItem() {
        if (mActionMenuItems.isEmpty() || mActionMenuSourceItem == null) {
            return;
        }
        ActionMenuItem item = mActionMenuItems.get(mFocusedActionMenuItem);
        if (item.action == null) {
            openNotificationItem(mActionMenuSourceItem);
            return;
        }
        performNotificationAction(mActionMenuSourceItem, item);
    }

    private void openNotificationItem(NotificationItem item) {
        StatusBarNotification sbn = item.sbn;
        PendingIntent intent = sbn.getNotification().contentIntent;
        if (intent == null) {
            return;
        }
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            intent.send(mContext, 0, null, null, null, null, options.toBundle());
            if ((sbn.getNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0) {
                dismissFocusedNotification(true, false);
            }
            hide();
        } catch (PendingIntent.CanceledException e) {
            Slog.w(TAG, "Unable to open notification", e);
        }
    }

    private void performNotificationAction(NotificationItem sourceItem, ActionMenuItem menuItem) {
        RemoteInput remoteInput = getTextRemoteInput(menuItem.action);
        if (remoteInput != null) {
            showRemoteInputEditor(sourceItem, menuItem, remoteInput);
            return;
        }
        try {
            sendNotificationAction(sourceItem, menuItem, null);
            closeNotificationActionMenu();
            scheduleNotificationOverlayRefresh();
        } catch (PendingIntent.CanceledException | RemoteException e) {
            Slog.w(TAG, "Unable to execute notification action", e);
        }
    }

    private void showRemoteInputEditor(NotificationItem sourceItem, ActionMenuItem menuItem,
            RemoteInput remoteInput) {
        mShowingNotificationActionMenu = false;
        mActionMenuItems.clear();
        mActionMenuSourceItem = sourceItem;
        mShowingRemoteInputEditor = true;
        mRemoteInputSourceItem = sourceItem;
        mRemoteInputActionItem = menuItem;
        mRemoteInput = remoteInput;
        mNotificationsContainer.removeAllViews();

        LinearLayout card = new LinearLayout(mContext);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setMinimumHeight(dp(132));
        card.setClickable(true);

        TextView title = new TextView(mContext);
        title.setText(menuItem.label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(mContext);
        hint.setText(TextUtils.isEmpty(remoteInput.getLabel())
                ? "Type your message"
                : remoteInput.getLabel());
        hint.setTextColor(0xffc7d0d9);
        hint.setTextSize(12);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(6);
        card.addView(hint, hintParams);

        EditText input = new EditText(mContext);
        input.setTextColor(Color.WHITE);
        input.setHint(TextUtils.isEmpty(remoteInput.getLabel()) ? menuItem.label : remoteInput.getLabel());
        input.setHintTextColor(0xff7f8b95);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setBackground(makeBackground(0xff151d24, 0xff4c5963, dp(10)));
        input.setPadding(dp(10), dp(10), dp(10), dp(10));
        input.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
                return false;
            }
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    submitRemoteInput();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_MENU:
                    closeRemoteInputEditor(true);
                    return true;
                default:
                    return false;
            }
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                submitRemoteInput();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = dp(10);
        card.addView(input, inputParams);

        TextView footer = new TextView(mContext);
        footer.setText("Press center to send. Back to cancel.");
        footer.setTextColor(0xff8fd0ff);
        footer.setTextSize(11);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        footerParams.topMargin = dp(8);
        card.addView(footer, footerParams);

        mRemoteInputEditor = card;
        mRemoteInputEditText = input;
        mNotificationsContainer.addView(card, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT));
        mRemoteInputEditText.requestFocus();
        updateFocus();
        mRoot.post(() -> {
            if (mRemoteInputEditText != null) {
                mRemoteInputEditText.requestFocus();
                mRemoteInputEditText.setSelection(mRemoteInputEditText.getText().length());
            }
        });
    }

    private void closeRemoteInputEditor(boolean restoreActionMenu) {
        NotificationItem sourceItem = mRemoteInputSourceItem;
        ActionMenuItem actionItem = mRemoteInputActionItem;
        clearRemoteInputEditorState();
        if (restoreActionMenu && sourceItem != null) {
            showNotificationActionMenu(sourceItem, actionItem != null ? actionItem.actionIndex + 1 : 0);
            return;
        }
        refreshNotifications();
        mRoot.requestFocus();
        updateFocus();
    }

    private void submitRemoteInput() {
        if (mRemoteInputSourceItem == null || mRemoteInputActionItem == null || mRemoteInput == null
                || mRemoteInputEditText == null) {
            return;
        }
        CharSequence text = mRemoteInputEditText.getText();
        if (TextUtils.isEmpty(text)) {
            mRemoteInputEditText.setError("Type text");
            return;
        }

        Intent fillInIntent = new Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        Bundle results = new Bundle();
        results.putCharSequence(mRemoteInput.getResultKey(), text);
        RemoteInput.addResultsToIntent(new RemoteInput[] {mRemoteInput}, fillInIntent, results);
        RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_FREE_FORM_INPUT);

        try {
            sendNotificationAction(mRemoteInputSourceItem, mRemoteInputActionItem, fillInIntent);
            closeNotificationActionMenu();
            scheduleNotificationOverlayRefresh();
        } catch (PendingIntent.CanceledException | RemoteException e) {
            Slog.w(TAG, "Unable to send remote input action", e);
        }
    }

    private void sendNotificationAction(NotificationItem sourceItem, ActionMenuItem menuItem,
            Intent fillInIntent) throws PendingIntent.CanceledException, RemoteException {
        NotificationVisibility visibility = NotificationVisibility.obtain(
                sourceItem.sbn.getKey(),
                MathUtils.constrain(mFocusedNotification, 0,
                        Math.max(0, mNotificationItems.size() - 1)),
                mNotificationItems.size(),
                true,
                NotificationVisibility.NotificationLocation.LOCATION_MAIN_AREA);
        if (mStatusBarService != null) {
            mStatusBarService.onNotificationActionClick(
                    sourceItem.sbn.getKey(),
                    menuItem.actionIndex,
                    menuItem.action,
                    visibility,
                    false);
        }
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        menuItem.action.actionIntent.send(mContext, 0, fillInIntent, null, null, null,
                options.toBundle());
    }

    private void clearRemoteInputEditorState() {
        mShowingRemoteInputEditor = false;
        mRemoteInputSourceItem = null;
        mRemoteInputActionItem = null;
        mRemoteInput = null;
        mRemoteInputEditor = null;
        mRemoteInputEditText = null;
    }

    private void resetNotificationOverlayState() {
        clearRemoteInputEditorState();
        mShowingNotificationActionMenu = false;
        mActionMenuSourceItem = null;
        mActionMenuItems.clear();
    }

    private void setFocusedRow(int row) {
        mFocusedRow = MathUtils.constrain(row, ROW_FIRST_BUTTONS, LAST_ROW);
        updateFocus();
    }

    private void activateFocusedButton() {
        if (!isButtonRowFocused()) {
            return;
        }
        activateButton(getFocusedButtonIndex());
    }

    private void activateButton(int index) {
        try {
            switch (index) {
                case BUTTON_AIRPLANE:
                    toggleAirplaneMode();
                    break;
                case BUTTON_RINGER:
                    cycleRingerMode();
                    break;
                case BUTTON_DND:
                    toggleDoNotDisturb();
                    break;
                case BUTTON_HOTSPOT:
                    toggleWifiHotspot();
                    break;
                case BUTTON_BATTERY_SAVER:
                    toggleBatterySaver();
                    break;
                case BUTTON_TOUCHSCREEN:
                    toggleTouchscreen();
                    break;
                case BUTTON_MOBILE_DATA:
                    toggleMobileData();
                    break;
                case BUTTON_FLASHLIGHT:
                    toggleFlashlight();
                    break;
            }
        } catch (Exception e) {
            Slog.w(TAG, "Unable to activate quick setting", e);
        }
        updateState();
    }

    private void toggleAirplaneMode() {
        boolean enabled = isAirplaneModeOn();
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, enabled ? 0 : 1);
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.putExtra("state", !enabled);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void cycleRingerMode() {
        if (mAudioManager == null) {
            return;
        }

        int mode = mAudioManager.getRingerMode();
        int nextMode;
        if (mode == AudioManager.RINGER_MODE_SILENT) {
            nextMode = AudioManager.RINGER_MODE_VIBRATE;
        } else if (mode == AudioManager.RINGER_MODE_VIBRATE) {
            nextMode = AudioManager.RINGER_MODE_NORMAL;
        } else {
            nextMode = AudioManager.RINGER_MODE_SILENT;
        }
        mAudioManager.setRingerMode(nextMode);
    }

    private void toggleDoNotDisturb() {
        if (mNotificationManager == null) {
            return;
        }

        int filter = mNotificationManager.getCurrentInterruptionFilter();
        mNotificationManager.setInterruptionFilter(
                filter == NotificationManager.INTERRUPTION_FILTER_ALL
                        ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                        : NotificationManager.INTERRUPTION_FILTER_ALL);
    }

    private void toggleWifiHotspot() {
        if (mConnectivityManager == null) {
            return;
        }

        if (isWifiHotspotOn()) {
            mWifiHotspotEnabled = false;
            mConnectivityManager.stopTethering(ConnectivityManager.TETHERING_WIFI);
        } else {
            mConnectivityManager.startTethering(ConnectivityManager.TETHERING_WIFI,
                    false, new ConnectivityManager.OnStartTetheringCallback() {
                        @Override
                        public void onTetheringStarted() {
                            mWifiHotspotEnabled = true;
                            mRoot.post(QuickSettingsOverlay.this::updateState);
                        }

                        @Override
                        public void onTetheringFailed() {
                            mWifiHotspotEnabled = false;
                            mRoot.post(QuickSettingsOverlay.this::updateState);
                            Slog.w(TAG, "Unable to start Wi-Fi hotspot");
                        }
                    });
        }
    }

    private void toggleBatterySaver() {
        if (mPowerManager == null) {
            return;
        }
        mPowerManager.setPowerSaveModeEnabled(!mPowerManager.isPowerSaveMode());
    }

    private void toggleTouchscreen() {
        refreshTouchscreenState();
        setTouchscreenEnabled(!mTouchscreenEnabled);
    }

    private void toggleMobileData() {
        if (mTelephonyManager == null) {
            return;
        }
        mTelephonyManager.setDataEnabled(!mTelephonyManager.isDataEnabled());
    }

    private void toggleFlashlight() throws CameraAccessException {
        if (mCameraManager == null) {
            return;
        }
        String cameraId = getRearFlashCameraId();
        if (cameraId == null) {
            return;
        }
        boolean enabled = !mFlashlightEnabled;
        mCameraManager.setTorchMode(cameraId, enabled);
        mFlashlightEnabled = enabled;
    }

    private void changeFocusedVolume(boolean raise) {
        if (mAudioManager == null) {
            return;
        }

        try {
            int stream = getFocusedVolumeControl().stream;
            int min = mAudioManager.getStreamMinVolume(stream);
            int max = mAudioManager.getStreamMaxVolume(stream);
            int current = mAudioManager.getStreamVolume(stream);
            int next = MathUtils.constrain(current + (raise ? 1 : -1), min, max);
            mAudioManager.setStreamVolume(stream, next, 0);
            updateState();
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to change volume", e);
        }
    }

    private void setVolumeFromTouch(int index, float x, int width) {
        if (mAudioManager == null || width <= 0) {
            return;
        }

        try {
            VolumeControl control = mVolumeControls[index];
            int min = mAudioManager.getStreamMinVolume(control.stream);
            int max = mAudioManager.getStreamMaxVolume(control.stream);
            float fraction = MathUtils.constrain(x / width, 0f, 1f);
            int volume = MathUtils.constrain(Math.round(min + fraction * (max - min)), min, max);
            mFocusedRow = FIRST_VOLUME_ROW + index;
            mAudioManager.setStreamVolume(control.stream, volume, 0);
            updateState();
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to set volume from touch", e);
        }
    }

    private void changeBrightness(boolean raise) {
        float gammaFraction = getBrightnessGammaFraction();
        if (Float.isNaN(gammaFraction)) {
            return;
        }
        float nextFraction = MathUtils.constrain(
                gammaFraction + (raise ? 1f : -1f) / BRIGHTNESS_STEPS, 0f, 1f);
        setBrightnessFraction(nextFraction);
    }

    private void setBrightnessFromTouch(float x, int width) {
        if (width <= 0) {
            return;
        }
        float fraction = MathUtils.constrain(x / width, 0f, 1f);
        mFocusedRow = ROW_BRIGHTNESS;
        setBrightnessFraction(fraction);
    }

    private void setBrightnessFraction(float fraction) {
        if (mDisplayManager == null || mPowerManager == null) {
            return;
        }

        try {
            setAutoBrightnessEnabled(false);
            float minBrightness = getMinimumBrightness();
            float maxBrightness = getMaximumBrightness();
            float minGamma = BrightnessUtils.convertLinearToGamma(minBrightness);
            float maxGamma = BrightnessUtils.convertLinearToGamma(maxBrightness);
            float gammaBrightness = minGamma + (maxGamma - minGamma)
                    * MathUtils.constrain(fraction, 0f, 1f);
            float linearBrightness = BrightnessUtils.convertGammaToLinear(gammaBrightness);
            mDisplayManager.setBrightness(Display.DEFAULT_DISPLAY, MathUtils.constrain(
                    linearBrightness, minBrightness, maxBrightness));
            updateState();
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to change brightness", e);
        }
    }

    private void updateState() {
        try {
            refreshTouchscreenState();
            mButtonStates[BUTTON_AIRPLANE].setText(isAirplaneModeOn() ? "ON" : "OFF");
            mButtonStates[BUTTON_RINGER].setText(getRingerLabel());
            mButtonStates[BUTTON_DND].setText(isDoNotDisturbOn() ? "ON" : "OFF");
            mButtonStates[BUTTON_HOTSPOT].setText(isWifiHotspotOn() ? "ON" : "OFF");
            mButtonStates[BUTTON_BATTERY_SAVER].setText(isBatterySaverOn() ? "ON" : "OFF");
            mButtonStates[BUTTON_TOUCHSCREEN].setText(mTouchscreenEnabled ? "ON" : "OFF");
            mButtonStates[BUTTON_MOBILE_DATA].setText(isMobileDataOn() ? "ON" : "OFF");
            mButtonStates[BUTTON_FLASHLIGHT].setText(mFlashlightEnabled ? "ON" : "OFF");
            updateBrightness();
            updateVolumes();
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to refresh quick settings overlay", e);
        }
        updateFocus();
    }

    private void updateFocus() {
        if (mMode == MODE_NOTIFICATIONS) {
            if (mShowingRemoteInputEditor) {
                if (mRemoteInputEditor != null) {
                    mRemoteInputEditor.setBackground(makeBackground(0xff2d6cdf,
                            0xffffffff, dp(12)));
                }
                mCaption.setText(mRemoteInputActionItem != null
                        ? mRemoteInputActionItem.label : "Reply");
                scrollFocusedItemIntoView();
                return;
            }
            if (mShowingNotificationActionMenu) {
                for (int i = 0; i < mActionMenuItems.size(); i++) {
                    ActionMenuItem item = mActionMenuItems.get(i);
                    boolean focused = i == mFocusedActionMenuItem;
                    item.row.setBackground(makeBackground(focused ? 0xff2d6cdf : 0xff263038,
                            focused ? 0xffffffff : 0xff4c5963, dp(12)));
                }
                mCaption.setText(mActionMenuSourceItem != null
                        ? getNotificationTitle(mActionMenuSourceItem.sbn) : "Notification");
                scrollFocusedItemIntoView();
                return;
            }
            for (int i = 0; i < mNotificationItems.size(); i++) {
                NotificationItem item = mNotificationItems.get(i);
                boolean focused = i == mFocusedNotification;
                item.row.setBackground(makeBackground(focused ? 0xff2d6cdf : 0xff263038,
                        focused ? 0xffffffff : 0xff4c5963, dp(12)));
            }
            mCaption.setText("Notifications");
            scrollFocusedItemIntoView();
            return;
        }

        for (int i = 0; i < mButtons.length; i++) {
            boolean focused = isButtonRowFocused() && i == getFocusedButtonIndex();
            mButtons[i].setBackground(makeBackground(focused ? 0xff2d6cdf : 0xff263038,
                    focused ? 0xffffffff : 0xff4c5963, dp(12)));
        }
        boolean brightnessFocused = isBrightnessRowFocused();
        mBrightnessControl.row.setBackground(makeBackground(
                brightnessFocused ? 0xff2d6cdf : 0xff263038,
                brightnessFocused ? 0xffffffff : 0xff4c5963, dp(12)));
        for (int i = 0; i < mVolumeControls.length; i++) {
            VolumeControl control = mVolumeControls[i];
            boolean focused = mFocusedRow == FIRST_VOLUME_ROW + i;
            control.row.setBackground(makeBackground(focused ? 0xff2d6cdf : 0xff263038,
                    focused ? 0xffffffff : 0xff4c5963, dp(12)));
        }
        if (isBrightnessRowFocused()) {
            mCaption.setText(getBrightnessCaption());
        } else if (isVolumeRowFocused()) {
            mCaption.setText(getFocusedVolumeCaption());
        } else {
            mCaption.setText(BUTTON_LABELS[getFocusedButtonIndex()]);
        }
        scrollFocusedItemIntoView();
    }

    private void updateBrightness() {
        boolean autoBrightness = isAutoBrightnessOn();
        mBrightnessControl.bar.setAlpha(autoBrightness ? 0.45f : 1f);
        mBrightnessControl.autoLabel.setVisibility(autoBrightness ? View.VISIBLE : View.GONE);
        float fraction = getBrightnessGammaFraction();
        setSliderFill(mBrightnessControl.fill, mBrightnessControl.empty,
                Float.isNaN(fraction) ? 0f : fraction);
    }

    private void updateVolumes() {
        if (mAudioManager == null) {
            for (VolumeControl control : mVolumeControls) {
                setSliderFill(control.fill, control.empty, 0f);
            }
            return;
        }

        for (VolumeControl control : mVolumeControls) {
            int min = mAudioManager.getStreamMinVolume(control.stream);
            int max = mAudioManager.getStreamMaxVolume(control.stream);
            int current = mAudioManager.getStreamVolume(control.stream);
            float fraction = MathUtils.constrain((current - min) / (float) Math.max(1, max - min),
                    0f, 1f);
            setSliderFill(control.fill, control.empty, fraction);
        }
    }

    private void setSliderFill(View fill, View empty, float fraction) {
        LinearLayout.LayoutParams fillParams =
                (LinearLayout.LayoutParams) fill.getLayoutParams();
        LinearLayout.LayoutParams emptyParams =
                (LinearLayout.LayoutParams) empty.getLayoutParams();
        fillParams.weight = fraction;
        emptyParams.weight = 1f - fraction;
        fill.setLayoutParams(fillParams);
        empty.setLayoutParams(emptyParams);
    }

    private boolean isAirplaneModeOn() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }

    private String getRingerLabel() {
        if (mAudioManager == null) {
            return "N/A";
        }

        switch (mAudioManager.getRingerMode()) {
            case AudioManager.RINGER_MODE_SILENT:
                return "Silent";
            case AudioManager.RINGER_MODE_VIBRATE:
                return "Vibrate";
            case AudioManager.RINGER_MODE_NORMAL:
                return "Ring";
            default:
                return "Unknown";
        }
    }

    private boolean isDoNotDisturbOn() {
        return mNotificationManager != null
                && mNotificationManager.getCurrentInterruptionFilter()
                        != NotificationManager.INTERRUPTION_FILTER_ALL;
    }

    private boolean isWifiHotspotOn() {
        return mWifiHotspotEnabled;
    }

    private boolean isBatterySaverOn() {
        return mPowerManager != null && mPowerManager.isPowerSaveMode();
    }

    private boolean isMobileDataOn() {
        return mTelephonyManager != null && mTelephonyManager.isDataEnabled();
    }

    private String getRearFlashCameraId() throws CameraAccessException {
        if (mRearFlashCameraId != null || mCameraManager == null) {
            return mRearFlashCameraId;
        }
        for (String id : mCameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(id);
            Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (flashAvailable != null && flashAvailable
                    && lensFacing != null
                    && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                mRearFlashCameraId = id;
                break;
            }
        }
        return mRearFlashCameraId;
    }

    private void refreshTouchscreenState() {
        mTouchscreenDeviceIds.clear();
        if (mInputManager == null) {
            mTouchscreenEnabled = true;
            return;
        }
        try {
            int[] ids = mInputManager.getInputDeviceIds();
            for (int id : ids) {
                InputDevice device = mInputManager.getInputDevice(id);
                if (device == null || device.isVirtual()) {
                    continue;
                }
                if ((device.getSources() & InputDevice.SOURCE_TOUCHSCREEN)
                        == InputDevice.SOURCE_TOUCHSCREEN) {
                    mTouchscreenDeviceIds.add(id);
                }
            }
            if (mTouchscreenDeviceIds.isEmpty()) {
                mTouchscreenEnabled = true;
                return;
            }
            boolean allDisabled = true;
            for (int id : mTouchscreenDeviceIds) {
                if (mInputManager.isInputDeviceEnabled(id)) {
                    allDisabled = false;
                    break;
                }
            }
            mTouchscreenEnabled = !allDisabled;
        } catch (SecurityException e) {
            Slog.e(TAG, "Missing permission to query input devices", e);
            mTouchscreenEnabled = true;
            mTouchscreenDeviceIds.clear();
        } catch (RuntimeException e) {
            Slog.e(TAG, "Failed to inspect touchscreen devices", e);
            mTouchscreenEnabled = true;
        }
    }

    private void setTouchscreenEnabled(boolean enabled) {
        for (int id : mTouchscreenDeviceIds) {
            try {
                if (enabled) {
                    mInputManager.enableInputDevice(id);
                } else {
                    mInputManager.disableInputDevice(id);
                }
            } catch (SecurityException e) {
                Slog.e(TAG, "Permission denied toggling input device " + id, e);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Failed toggling input device " + id, e);
            }
        }
        refreshTouchscreenState();
    }

    private boolean isButtonRowFocused() {
        return mFocusedRow < BUTTON_ROW_COUNT;
    }

    private boolean isBrightnessRowFocused() {
        return mFocusedRow == ROW_BRIGHTNESS;
    }

    private boolean isVolumeRowFocused() {
        return mFocusedRow >= FIRST_VOLUME_ROW;
    }

    private VolumeControl getFocusedVolumeControl() {
        return mVolumeControls[mFocusedRow - FIRST_VOLUME_ROW];
    }

    private int getFocusedButtonIndex() {
        return mFocusedRow * BUTTONS_PER_ROW + mFocusedButton;
    }

    private void toggleAutoBrightness() {
        if (!mAutomaticBrightnessAvailable) {
            return;
        }
        setAutoBrightnessEnabled(!isAutoBrightnessOn());
        updateState();
    }

    private void setAutoBrightnessEnabled(boolean enabled) {
        if (!mAutomaticBrightnessAvailable) {
            return;
        }
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                enabled ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                UserHandle.USER_CURRENT_OR_SELF);
    }

    private boolean isAutoBrightnessOn() {
        if (!mAutomaticBrightnessAvailable) {
            return false;
        }
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                UserHandle.USER_CURRENT_OR_SELF)
                == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    }

    private float getBrightnessGammaFraction() {
        if (mDisplayManager == null || mPowerManager == null) {
            return Float.NaN;
        }
        float brightness = mDisplayManager.getBrightness(Display.DEFAULT_DISPLAY);
        if (Float.isNaN(brightness)) {
            return Float.NaN;
        }
        float minBrightness = getMinimumBrightness();
        float maxBrightness = getMaximumBrightness();
        float minGamma = BrightnessUtils.convertLinearToGamma(minBrightness);
        float maxGamma = BrightnessUtils.convertLinearToGamma(maxBrightness);
        float currentGamma = BrightnessUtils.convertLinearToGamma(
                MathUtils.constrain(brightness, minBrightness, maxBrightness));
        return MathUtils.constrain((currentGamma - minGamma) / Math.max(0.0001f,
                maxGamma - minGamma), 0f, 1f);
    }

    private float getMinimumBrightness() {
        return mPowerManager != null
                ? mPowerManager.getBrightnessConstraint(
                        PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM)
                : 0f;
    }

    private float getMaximumBrightness() {
        return mPowerManager != null
                ? mPowerManager.getBrightnessConstraint(
                        PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MAXIMUM)
                : 1f;
    }

    private String getBrightnessCaption() {
        if (isAutoBrightnessOn()) {
            return "Brightness Auto";
        }
        float fraction = getBrightnessGammaFraction();
        int percentage = Float.isNaN(fraction) ? 0 : Math.round(fraction * 100f);
        return "Brightness " + percentage + "%";
    }

    private int getOverlayTopOffset() {
        return Math.max(0, dp(36 - VOLUME_ROW_HEIGHT_DP / 8));
    }

    private int getMaxBodyHeight() {
        int screenHeight = mContext.getResources().getDisplayMetrics().heightPixels;
        int reservedHeight = getOverlayTopOffset()
                + mRoot.getPaddingTop()
                + mRoot.getPaddingBottom()
                + dp(32 + 20);
        return Math.max(dp(160), screenHeight - reservedHeight);
    }

    private void scrollFocusedItemIntoView() {
        View focusedView = getFocusedView();
        if (focusedView == null || focusedView.getHeight() == 0) {
            return;
        }
        int focusedTop = getRelativeTop(focusedView, mScrollableContent);
        int focusedBottom = focusedTop + focusedView.getHeight();
        int visibleTop = mScrollView.getScrollY();
        int visibleBottom = visibleTop + mScrollView.getHeight();

        if (focusedTop < visibleTop) {
            mScrollView.scrollTo(0, focusedTop);
        } else if (focusedBottom > visibleBottom) {
            mScrollView.scrollTo(0, focusedBottom - mScrollView.getHeight());
        }
    }

    private View getFocusedView() {
        if (mMode == MODE_NOTIFICATIONS) {
            if (mShowingRemoteInputEditor) {
                return mRemoteInputEditor;
            }
            if (mShowingNotificationActionMenu) {
                if (mActionMenuItems.isEmpty()
                        || mFocusedActionMenuItem >= mActionMenuItems.size()) {
                    return null;
                }
                return mActionMenuItems.get(mFocusedActionMenuItem).row;
            }
            if (mNotificationItems.isEmpty() || mFocusedNotification >= mNotificationItems.size()) {
                return null;
            }
            return mNotificationItems.get(mFocusedNotification).row;
        }
        if (isBrightnessRowFocused()) {
            return mBrightnessControl.row;
        }
        if (isVolumeRowFocused()) {
            return getFocusedVolumeControl().row;
        }
        if (mFocusedRow >= 0 && mFocusedRow < mButtonRows.length) {
            return mButtonRows[mFocusedRow];
        }
        return null;
    }

    private int getRelativeTop(View child, View ancestor) {
        int top = 0;
        View current = child;
        while (current != null && current != ancestor) {
            top += current.getTop();
            if (!(current.getParent() instanceof View)) {
                break;
            }
            current = (View) current.getParent();
        }
        return top;
    }

    private String getFocusedVolumeCaption() {
        VolumeControl control = getFocusedVolumeControl();
        if (mAudioManager == null) {
            return control.label;
        }
        int current = mAudioManager.getStreamVolume(control.stream);
        int max = mAudioManager.getStreamMaxVolume(control.stream);
        return control.label + " " + current + "/" + max;
    }

    private int dp(int value) {
        return (int) (value * mContext.getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable makeBackground(int color, int strokeColor, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeColor != 0) {
            drawable.setStroke(dp(2), strokeColor);
        }
        return drawable;
    }

    private static final class VolumeControl {
        final String label;
        final int stream;
        final LinearLayout row;
        final View fill;
        final View empty;

        VolumeControl(String label, int stream, LinearLayout row, View fill, View empty) {
            this.label = label;
            this.stream = stream;
            this.row = row;
            this.fill = fill;
            this.empty = empty;
        }
    }

    private static final class BrightnessControl {
        final LinearLayout row;
        final View bar;
        final View fill;
        final View empty;
        final TextView autoLabel;

        BrightnessControl(LinearLayout row, View bar, View fill, View empty,
                TextView autoLabel) {
            this.row = row;
            this.bar = bar;
            this.fill = fill;
            this.empty = empty;
            this.autoLabel = autoLabel;
        }
    }

    private static final class ActionMenuItem {
        final String label;
        final Notification.Action action;
        final int actionIndex;
        final LinearLayout row;

        ActionMenuItem(String label, Notification.Action action, int actionIndex,
                LinearLayout row) {
            this.label = label;
            this.action = action;
            this.actionIndex = actionIndex;
            this.row = row;
        }
    }

    private static final class NotificationItem {
        final StatusBarNotification sbn;
        final LinearLayout row;

        NotificationItem(StatusBarNotification sbn, LinearLayout row) {
            this.sbn = sbn;
            this.row = row;
        }
    }

}
