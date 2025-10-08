package com.android.server.policy;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Slog;

import java.util.List;

/**
 * Approximates proximity when no hardware sensor exists.
 *
 * Activation (framework-wide, app-agnostic):
 *   - Enabled while audio is routed to the BUILTIN_EARPIECE for voice/VoIP use
 *     (MODE_IN_CALL / MODE_IN_COMMUNICATION, or active playback with USAGE_VOICE_COMMUNICATION,
 *     or active recording with AudioSource.VOICE_COMMUNICATION), and
 *   - Disabled when audio goes to speaker, Bluetooth SCO, wired/USB headsets, etc.
 *
 * When active, it uses gravity/accelerometer to infer "near ear" and notifies the
 * listener. Policy can then toggle PROXIMITY_SCREEN_OFF_WAKE_LOCK accordingly.
 */
final class HeuristicProximityController {
    interface Listener {
        /** Called on the main thread whenever the near/away state changes. */
        void onNearChanged(boolean near);
    }

    // --- Tunables (consider exposing as overlay resources if desired) ---
    private static final long ENTER_MS = 350;   // debounce entering "near"
    private static final long EXIT_MS  = 450;   // debounce exiting "near"
    private static final float MIN_G = 7.0f;    // sanity range for |g|
    private static final float MAX_G = 13.0f;
    private static final float ENTER_TILT_DEG = 25.0f; // Y-axis ~ vertical
    private static final float ENTER_ABS_GZ   = 4.0f;  // not laying flat

    // Audio state settling/polling
    private static final long SETTLE_MS = 600;        // require this long of "clean" state to treat voice as ended
    private static final long POLL_INTERVAL_MS = 200; // how often to poll after a change
    private static final long POLL_BURST_DURATION_MS = 30000; // poll for up to this long after a change

    private final Context mContext;
    private final Listener mListener;
    private final Handler mHandler;
    private final SensorManager mSM;
    private final AudioManager mAM;

    private boolean mCallActive;      // Currently on a call
    // Sensor state
    private boolean mActive;          // whether we should evaluate orientation at all
    private boolean mNear;            // current debounced "near" state
    private long mCandidateSince;     // debounce timer
    private float mGx, mGy, mGz;      // latest gravity/accel sample

    // Audio settle & polling
    private long mLastVoiceFalseTimestamp = 0L;
    private long mLastAudioChangeRealtime = 0L;
    private boolean mPollingScheduled = false;

    HeuristicProximityController(Context ctx, Listener l) {
        mContext = ctx;
        mListener = l;
        mHandler = new Handler(Looper.getMainLooper());
        mSM = ctx.getSystemService(SensorManager.class);
        mAM = ctx.getSystemService(AudioManager.class);
        Slog.d("Dumbdroid proximity", "Registering listeners");
        // --- Observe audio routing/activity so we catch both telephony and VoIP ---
        // Mode changes are the most reliable signal at call start/end for both telephony and VoIP.
        mAM.addOnModeChangedListener(ctx.getMainExecutor(), mOnModeChanged);

        // Playback (e.g., VOICE_COMMUNICATION streams)
        mAM.registerAudioPlaybackCallback(mPlaybackCb, mHandler);

        // Recording (most VoIP records MIC with VOICE_COMMUNICATION source)
        mAM.registerAudioRecordingCallback(mRecordingCb, mHandler);

        // Device changes (headset plug/unplug, BT connect/disconnect, etc.)
        mAM.registerAudioDeviceCallback(mDeviceCb, mHandler);

        // Initial evaluation
        markAudioChangeAndRefresh();
    }

    // ---------------------- Audio listeners ----------------------

    private final AudioManager.OnModeChangedListener mOnModeChanged =
            new AudioManager.OnModeChangedListener() {
                @Override
                public void onModeChanged(int mode) {
                    mCallActive = (mode == 2 || mode == 3 || mode == 1);
                    markAudioChangeAndRefresh();
                    Slog.d("Dumbdroid proximity", "Mode changed: " + mode);
                    if (!mCallActive)
                        setActive(false);
                }
            };

    private final AudioManager.AudioPlaybackCallback mPlaybackCb =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
         //           markAudioChangeAndRefresh();
                    Slog.d("Dumbdroid proximity", "Audio playback changed");
                }
            };

    private final AudioManager.AudioRecordingCallback mRecordingCb =
            new AudioManager.AudioRecordingCallback() {
                @Override
                public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
           //         markAudioChangeAndRefresh();
                    Slog.d("Dumbdroid proximity", "Audio recording changed");
                }
            };

    private final AudioDeviceCallback mDeviceCb = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            markAudioChangeAndRefresh();
            Slog.d("Dumbdroid proximity", "Audio device added");
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            markAudioChangeAndRefresh();
            Slog.d("Dumbdroid proximity", "Audio device remvoed");
        }
    };

    public void markAudioChangeAndRefresh() {
        mLastAudioChangeRealtime = SystemClock.elapsedRealtime();
        refreshActiveFromAudioRoute();
        schedulePollingBurst(); // keep nudging for a short while to catch laggy updates
    }

    private void schedulePollingBurst() {
        if (mPollingScheduled) return;
        mPollingScheduled = true;
        mHandler.post(new Runnable() {
            @Override public void run() {
                long elapsed = SystemClock.elapsedRealtime() - mLastAudioChangeRealtime;
		if (mActive)
                   refreshActiveFromAudioRoute();
                if (elapsed < POLL_BURST_DURATION_MS) {
                    mHandler.postDelayed(this, POLL_INTERVAL_MS);
                } else {
                    mPollingScheduled = false;
                }
            }
        });
    }

    /**
     * Consider the heuristic "active" when:
     *  - The system is in a voice communication scenario (MODE_IN_CALL or MODE_IN_COMMUNICATION,
     *    OR any active playback uses USAGE_VOICE_COMMUNICATION,
     *    OR any active recording uses AudioSource.VOICE_COMMUNICATION),
     *  - AND the *output* device includes BUILTIN_EARPIECE,
     *  - AND we're not on speakerphone, not on BT SCO, and not on a wired/USB headset.
     *
     * Also apply a small "settle" window after end-of-call so we don't get stuck true.
     */
    private void refreshActiveFromAudioRoute() {
        boolean voiceUse = isVoiceUseOngoingWithSettle();
        boolean earpiece = isEarpieceActive();
        boolean blockedRoutes = isSpeakerOrHeadsetOrBtActive();
        Slog.d("Dumbdroid proximity", "Refresh " + voiceUse + " " + earpiece + " " + blockedRoutes);
        boolean shouldBeActive = voiceUse && earpiece && !blockedRoutes;
        setActive(shouldBeActive);
    }

    private boolean isVoiceUseOngoingWithSettle() {
        boolean ongoing = isVoiceUseOngoingRaw();

        if (!ongoing) {
            // Track the last time we observed "no voice use" and only treat it as ended
            // once it stayed clear for SETTLE_MS (handles teardown lag).
            long now = SystemClock.elapsedRealtime();
            if (mLastVoiceFalseTimestamp == 0L) {
                mLastVoiceFalseTimestamp = now;
            }
            if ((now - mLastVoiceFalseTimestamp) < SETTLE_MS) {
                return true; // still within settle window => pretend ongoing
            }
            return false;
        } else {
            // Reset the false timer while ongoing
            mLastVoiceFalseTimestamp = 0L;
            return true;
        }
    }

    private boolean isVoiceUseOngoingRaw() {
        final int mode = mAM.getMode();
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
            return true;
        }
        // Playback side: look for active VOICE_COMMUNICATION usage
        try {
            List<AudioPlaybackConfiguration> plays = mAM.getActivePlaybackConfigurations();
            for (AudioPlaybackConfiguration pc : plays) {
                AudioAttributes aa = pc.getAudioAttributes();
                if (aa != null && aa.getUsage() == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        // Recording side: look for VOICE_COMMUNICATION source
        try {
            List<AudioRecordingConfiguration> recs = mAM.getActiveRecordingConfigurations();
            for (AudioRecordingConfiguration rc : recs) {
                int src = rc.getClientAudioSource();
                if (src == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    private boolean isEarpieceActive() {
        // Prefer inspecting active playback routes; if unavailable, fall back to devices list.
        try {
            List<AudioPlaybackConfiguration> plays = mAM.getActivePlaybackConfigurations();
            for (AudioPlaybackConfiguration pc : plays) {
                AudioAttributes aa = pc.getAudioAttributes();
                if (aa == null) continue;
                // We care about voice comm specifically; ignore media/music.
                if (aa.getUsage() != AudioAttributes.USAGE_VOICE_COMMUNICATION) continue;
                AudioDeviceInfo dev = pc.getAudioDeviceInfo();
                if (dev != null && dev.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        // Fallback heuristic: if the earpiece exists and no conflicting routes are active,
        // assume earpiece (some devices don't expose per-config device cleanly).
        AudioDeviceInfo[] outs = mAM.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        boolean hasEarpiece = false;
        for (AudioDeviceInfo d : outs) {
            if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                hasEarpiece = true; break;
            }
        }
        if (!hasEarpiece) return false;
        return !isSpeakerOrHeadsetOrBtActive();
    }

    private boolean isSpeakerOrHeadsetOrBtActive() {
        if (mAM.isSpeakerphoneOn() || mAM.isBluetoothScoOn()) return true;

        // Check active playback devices for BT / wired / USB headsets.
        try {
            List<AudioPlaybackConfiguration> plays = mAM.getActivePlaybackConfigurations();
            for (AudioPlaybackConfiguration pc : plays) {
                AudioDeviceInfo dev = pc.getAudioDeviceInfo();
                if (dev != null && isHeadsetOrBt(dev.getType())) return true;
            }
        } catch (Throwable ignored) {}

        // Fallback: scan connected output devices.
        AudioDeviceInfo[] outs = mAM.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo d : outs) {
            if (isHeadsetOrBt(d.getType())) return true;
        }
        return false;
    }

    private static boolean isHeadsetOrBt(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: // some stacks may report this briefly
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return true;
            default:
                return false;
        }
    }

    // ---------------------- Activation & sensors ----------------------

    private void setActive(boolean active) {
        Slog.d("Dumbdroid proximity", "active: " + active);
        if (active == mActive)
            return;
        if (!mCallActive && active)
            return;
        mActive = active;
        if (mActive) {
            Sensor grav = null;//mSM.getDefaultSensor(Sensor.TYPE_GRAVITY);
            Sensor accel = mSM.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            Sensor chosen = (grav != null) ? grav : accel;
            if (chosen != null) {
                Slog.d("Dumbdroid proximity", "Listening to sensor " + (chosen == grav ? "gravity" : "accelero"));
                mSM.registerListener(mSensorListener, chosen, SensorManager.SENSOR_DELAY_GAME, mHandler);
            } else
                Slog.d("Dumbdroid proximity", "Null sensor");
        } else {
            mSM.unregisterListener(mSensorListener);
            Slog.d("Dumbdroid proximity", "Stop listening to sensor");
            setNear(false); // ensure release via listener
        }
    }

    private final SensorEventListener mSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent e) {
            if (e.sensor.getType() == Sensor.TYPE_GRAVITY
                    || e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                mGx = e.values[0];
                mGy = e.values[1];
                mGz = e.values[2];
                evaluate();
            }
        }

        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    // ---------------------- Orientation heuristic ----------------------

    private void evaluate() {
        final boolean candidate = computeNear();
	Slog.d("Dumbdroid proximity", "Near approx " + candidate);
        final long now = SystemClock.elapsedRealtime();

        if (candidate && !mNear) {
            if (mCandidateSince == 0) mCandidateSince = now;
            if (now - mCandidateSince > ENTER_MS) setNear(true);
        } else if (!candidate && mNear) {
            if (mCandidateSince == 0) mCandidateSince = now;
            if (now - mCandidateSince > EXIT_MS) setNear(false);
        } else if (candidate == mNear) {
            mCandidateSince = 0; // stable, clear timer
        }
    }

    private static final float VERTICAL_GRAVITY_THRESHOLD = SensorManager.GRAVITY_EARTH * 0.70710678f;

    private boolean computeNear() {
	Slog.d("Dumbdroid proximity", "Z: " + mGz);
        boolean res = Math.abs(mGz) < VERTICAL_GRAVITY_THRESHOLD;
	Slog.d("Dumbdroid proximity", "Vertical: " + res);
        return res;
/*        final float g = (float) Math.sqrt(mGx * mGx + mGy * mGy + mGz * mGz);
        if (g < MIN_G || g > MAX_G) return false; // ignore bogus spikes

        // Angle between device Y-axis and gravity. 0° = perfectly upright portrait.
        final float cosTheta = Math.abs(mGy) / g;
        final float tiltDeg = (float) Math.toDegrees(Math.acos(cosTheta));
        final boolean upright = tiltDeg < ENTER_TILT_DEG;

        // Not lying flat: screen (Z) roughly perpendicular to ground when held to ear.
	Slog.d("Dumbdroid proximity", "mGz " + mGz);
        final boolean notFlat = Math.abs(mGz) < ENTER_ABS_GZ;

        return upright && notFlat;*/
    }

    private void setNear(boolean near) {
        if (near == mNear) return;
        mNear = near;
        mListener.onNearChanged(mNear);
    }
}

