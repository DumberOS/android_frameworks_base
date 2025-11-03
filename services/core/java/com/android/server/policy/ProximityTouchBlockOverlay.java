package com.android.server.policy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Binder;
import android.os.Looper;
import android.os.Handler;
import android.util.Slog;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.app.StatusBarManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.content.res.Resources;
import android.os.IBinder;
import android.os.ServiceManager;
import android.os.UserHandle;
import com.android.internal.statusbar.IStatusBarService;


/**
 * Fullscreen, dim overlay that CONSUMES all touch using TYPE_INPUT_CONSUMER.
 * Toggle from PhoneWindowManager when the heuristic says "near".
 */
final class ProximityTouchBlockOverlay {
    private static final String TAG = "ProxTouchBlock";

    private final Context mContext;
    private final WindowManager mWm;

    private boolean mShown;
    private StatusBarManager mSbm;
    private TextView mMessageView;
    private FrameLayout mRoot;
    private CharSequence mMessage;
    private final Handler mUi;
    private IStatusBarService mSbSvc;
    private final IBinder mSbToken = new Binder();

    ProximityTouchBlockOverlay(Context context) {
        mContext = context;
        mWm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mSbm = (StatusBarManager) context.getSystemService(Context.STATUS_BAR_SERVICE);
	mMessage = mContext.getText(com.android.internal.R.string.prox_touch_block_message);
	mUi = new Handler(Looper.getMainLooper());
        mSbSvc = IStatusBarService.Stub.asInterface(ServiceManager.getService("statusbar"));
    }

    private CharSequence loadFrameworkStringOrDefault(String name, CharSequence fallback) {
        try {
            Resources sys = Resources.getSystem();
            int id = sys.getIdentifier(name, "string", "android");
            if (id != 0) {
                return sys.getText(id);
            }
        } catch (Throwable ignored) {}
        return fallback;
    }

    boolean isShown() { return mShown; }

    void show() {
        if (mShown) return;
        if (mRoot == null) mRoot = buildView();
        try {
            final WindowManager.LayoutParams lp = buildLayoutParams();
            final long token = Binder.clearCallingIdentity();
            try {
                mWm.addView(mRoot, lp);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            mShown = true;
            if (mSbSvc != null) {
                Slog.i(TAG, "Disabling the status bar");
                mSbSvc.disableForUser(StatusBarManager.DISABLE_EXPAND, mSbToken,
                        mContext.getOpPackageName(), UserHandle.USER_CURRENT);
                mSbSvc.disable2ForUser(StatusBarManager.DISABLE2_QUICK_SETTINGS, mSbToken,
                        mContext.getOpPackageName(), UserHandle.USER_CURRENT);
            }
            Slog.i(TAG, "proximity block ENABLED");
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to show overlay", t);
        }
    }

    void hideThreadSafe() {
        mUi.post(this::hide);
    }

    void hide() {
        if (!mShown) return;
        try {
            if (mSbm != null) {
                Slog.i(TAG, "Enabling the status bar again");
                mSbm.disable(0);
                mSbm.disable2(0);
            }
            final long token = Binder.clearCallingIdentity();
            try {
                if (mRoot != null) mWm.removeViewImmediate(mRoot);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            mShown = false;
            Slog.i(TAG, "proximity block DISABLED");
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to hide overlay", t);
        }
    }

    private FrameLayout buildView() {
        // Root container that eats ALL touches.
        @SuppressLint("ClickableViewAccessibility")
        FrameLayout root = new FrameLayout(mContext) {
            @Override
            public boolean onTouchEvent(MotionEvent event) {
                // Eat everything so nothing reaches apps underneath.
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    Slog.i(TAG, "overlay consumed touch: (" + event.getX() + "," + event.getY() + ")");
                }
                return true;
            }
        };
        root.setClickable(true);
        root.setBackgroundColor(0xBB000000); // ~60% dim; use 0xFF000000 for full black
        root.setAlpha(1f);

        // Centered message
        mMessageView = new TextView(mContext);
        mMessageView.setText(mMessage);
        mMessageView.setTextColor(0xFFFFFFFF);
        mMessageView.setTextSize(18f);
        mMessageView.setGravity(Gravity.CENTER);
        mMessageView.setShadowLayer(4f, 0f, 0f, 0x80000000);
        mMessageView.setPadding(48, 48, 48, 48);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(mMessageView, lp);

        return root;
    }
    // ---------- View & LayoutParams ----------

    private WindowManager.LayoutParams buildLayoutParams() {
        final WindowManager.LayoutParams lp =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        // Interactive, input-consuming system window
                        WindowManager.LayoutParams.TYPE_INPUT_CONSUMER,
                        // Do NOT set NOT_TOUCHABLE; we WANT events.
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        PixelFormat.TRANSLUCENT);

        lp.setTitle("ProximityTouchBlockOverlay");
        // Trusted overlay avoids anti-tapjacking restrictions
        lp.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.alpha = 1f; // draw at full opacity; background alpha controls the dim
        lp.packageName = mContext.getOpPackageName();
        return lp;
    }
}
