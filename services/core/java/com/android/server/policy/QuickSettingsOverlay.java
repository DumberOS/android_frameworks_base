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
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyManager;
import android.util.MathUtils;
import android.util.Slog;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import com.android.server.LocalServices;
import com.android.server.notification.NotificationManagerInternal;

final class QuickSettingsOverlay {
    private static final String TAG = "QuickSettingsOverlay";

    private static final int MODE_NOTIFICATIONS = 0;
    private static final int MODE_QUICK_SETTINGS = 1;

    private static final int ROW_FIRST_BUTTONS = 0;
    private static final int ROW_SECOND_BUTTONS = 1;
    private static final int BUTTON_ROW_COUNT = 2;
    private static final int FIRST_VOLUME_ROW = BUTTON_ROW_COUNT;
    private static final int VOLUME_COUNT = 4;
    private static final int LAST_ROW = FIRST_VOLUME_ROW + VOLUME_COUNT - 1;
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
    private final NotificationManager mNotificationManager;
    private final INotificationManager mNotificationService;
    private final NotificationManagerInternal mNotificationManagerInternal;
    private final PowerManager mPowerManager;
    private final TelephonyManager mTelephonyManager;
    private final CameraManager mCameraManager;
    private final InputManager mInputManager;
    private final LinearLayout mRoot;
    private final TextView mCaption;
    private final LinearLayout mQuickSettingsContainer;
    private final LinearLayout mNotificationsContainer;
    private final LinearLayout[] mButtons = new LinearLayout[BUTTON_COUNT];
    private final TextView[] mButtonStates = new TextView[BUTTON_COUNT];
    private final VolumeControl[] mVolumeControls = new VolumeControl[VOLUME_COUNT];
    private final List<NotificationItem> mNotificationItems = new ArrayList<>();
    private final List<Integer> mTouchscreenDeviceIds = new ArrayList<>();

    private int mMode = MODE_NOTIFICATIONS;
    private int mFocusedRow = ROW_FIRST_BUTTONS;
    private int mFocusedButton;
    private int mFocusedNotification;
    private float mOverlayTouchDownX;
    private float mOverlayTouchDownY;
    private boolean mNotificationDismissInProgress;
    private boolean mWifiHotspotEnabled;
    private boolean mTouchscreenEnabled = true;
    private boolean mFlashlightEnabled;
    private String mRearFlashCameraId;
    private volatile boolean mShowing;

    QuickSettingsOverlay(Context context) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
        mAudioManager = context.getSystemService(AudioManager.class);
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mNotificationService = NotificationManager.getService();
        mNotificationManagerInternal = LocalServices.getService(NotificationManagerInternal.class);
        mPowerManager = context.getSystemService(PowerManager.class);
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        mCameraManager = context.getSystemService(CameraManager.class);
        mInputManager = context.getSystemService(InputManager.class);

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
                    hide();
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

        mQuickSettingsContainer = new LinearLayout(context);
        mQuickSettingsContainer.setOrientation(LinearLayout.VERTICAL);
        mRoot.addView(mQuickSettingsContainer, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT));

        mNotificationsContainer = new LinearLayout(context);
        mNotificationsContainer.setOrientation(LinearLayout.VERTICAL);
        mRoot.addView(mNotificationsContainer, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT));

        LinearLayout firstButtonRow = addButtonRow(mQuickSettingsContainer);
        addButton(firstButtonRow, BUTTON_AIRPLANE,
                com.android.internal.R.drawable.ic_lock_airplane_mode);
        addButton(firstButtonRow, BUTTON_RINGER,
                com.android.internal.R.drawable.ic_volume);
        addButton(firstButtonRow, BUTTON_DND,
                com.android.internal.R.drawable.ic_qs_dnd);
        addButton(firstButtonRow, BUTTON_HOTSPOT,
                com.android.internal.R.drawable.ic_hotspot_transient_animation);

        LinearLayout secondButtonRow = addButtonRow(mQuickSettingsContainer);
        addButton(secondButtonRow, BUTTON_BATTERY_SAVER,
                com.android.internal.R.drawable.ic_qs_battery_saver);
        addButton(secondButtonRow, BUTTON_TOUCHSCREEN,
                com.android.internal.R.drawable.ic_lock_open);
        addButton(secondButtonRow, BUTTON_MOBILE_DATA,
                com.android.internal.R.drawable.ic_menu);
        addButton(secondButtonRow, BUTTON_FLASHLIGHT,
                com.android.internal.R.drawable.ic_qs_flashlight);

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
        attrs.y = dp(36 - VOLUME_ROW_HEIGHT_DP / 8);
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
        if (mMode == MODE_NOTIFICATIONS) {
            refreshNotifications();
        } else {
            updateState();
        }
        mQuickSettingsContainer.setVisibility(mMode == MODE_QUICK_SETTINGS ? View.VISIBLE : View.GONE);
        mNotificationsContainer.setVisibility(mMode == MODE_NOTIFICATIONS ? View.VISIBLE : View.GONE);
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
            dismissFocusedNotification(right, true);
            return;
        }
        handleQuickSettingsLeftRight(right);
    }

    private void handleQuickSettingsLeftRight(boolean right) {
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
            activateFocusedNotification();
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
                    WindowManager.LayoutParams.MATCH_PARENT, dp(72)));
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
        row.setPadding(dp(12), dp(8), dp(12), dp(8));

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
        textColumn.addView(description, new LinearLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, dp(24)));

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

    private void dismissFocusedNotification(boolean right, boolean animate) {
        if (mNotificationItems.isEmpty() || mNotificationDismissInProgress) {
            return;
        }
        StatusBarNotification sbn = mNotificationItems.get(mFocusedNotification).sbn;
        if (mNotificationManagerInternal == null) {
            return;
        }
        try {
            mNotificationManagerInternal.cancelNotificationFromSystemListener(
                    sbn.getPackageName(), sbn.getTag(), sbn.getId(), sbn.getUserId());
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to dismiss notification", e);
            return;
        }
        if (animate) {
            animateFocusedNotificationDismiss(right);
        } else {
            removeFocusedNotificationFromOverlay(mNotificationItems.get(mFocusedNotification));
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
        StatusBarNotification sbn = mNotificationItems.get(mFocusedNotification).sbn;
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
            updateVolumes();
        } catch (RuntimeException e) {
            Slog.w(TAG, "Unable to refresh quick settings overlay", e);
        }
        updateFocus();
    }

    private void updateFocus() {
        if (mMode == MODE_NOTIFICATIONS) {
            for (int i = 0; i < mNotificationItems.size(); i++) {
                NotificationItem item = mNotificationItems.get(i);
                boolean focused = i == mFocusedNotification;
                item.row.setBackground(makeBackground(focused ? 0xff2d6cdf : 0xff263038,
                        focused ? 0xffffffff : 0xff4c5963, dp(12)));
            }
            mCaption.setText("Notifications");
            return;
        }

        for (int i = 0; i < mButtons.length; i++) {
            boolean focused = isButtonRowFocused() && i == getFocusedButtonIndex();
            mButtons[i].setBackground(makeBackground(focused ? 0xff2d6cdf : 0xff263038,
                    focused ? 0xffffffff : 0xff4c5963, dp(12)));
        }
        for (int i = 0; i < mVolumeControls.length; i++) {
            VolumeControl control = mVolumeControls[i];
            boolean focused = mFocusedRow == FIRST_VOLUME_ROW + i;
            control.row.setBackground(makeBackground(focused ? 0xff2d6cdf : 0xff263038,
                    focused ? 0xffffffff : 0xff4c5963, dp(12)));
        }
        mCaption.setText(isVolumeRowFocused()
                ? getFocusedVolumeCaption() : BUTTON_LABELS[getFocusedButtonIndex()]);
    }

    private void updateVolumes() {
        if (mAudioManager == null) {
            for (VolumeControl control : mVolumeControls) {
                setVolumeFill(control, 0f);
            }
            return;
        }

        for (VolumeControl control : mVolumeControls) {
            int min = mAudioManager.getStreamMinVolume(control.stream);
            int max = mAudioManager.getStreamMaxVolume(control.stream);
            int current = mAudioManager.getStreamVolume(control.stream);
            float fraction = MathUtils.constrain((current - min) / (float) Math.max(1, max - min),
                    0f, 1f);
            setVolumeFill(control, fraction);
        }
    }

    private void setVolumeFill(VolumeControl control, float fraction) {
        LinearLayout.LayoutParams fillParams =
                (LinearLayout.LayoutParams) control.fill.getLayoutParams();
        LinearLayout.LayoutParams emptyParams =
                (LinearLayout.LayoutParams) control.empty.getLayoutParams();
        fillParams.weight = fraction;
        emptyParams.weight = 1f - fraction;
        control.fill.setLayoutParams(fillParams);
        control.empty.setLayoutParams(emptyParams);
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

    private boolean isVolumeRowFocused() {
        return mFocusedRow >= FIRST_VOLUME_ROW;
    }

    private VolumeControl getFocusedVolumeControl() {
        return mVolumeControls[mFocusedRow - FIRST_VOLUME_ROW];
    }

    private int getFocusedButtonIndex() {
        return mFocusedRow * BUTTONS_PER_ROW + mFocusedButton;
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

    private static final class NotificationItem {
        final StatusBarNotification sbn;
        final LinearLayout row;

        NotificationItem(StatusBarNotification sbn, LinearLayout row) {
            this.sbn = sbn;
            this.row = row;
        }
    }

}
