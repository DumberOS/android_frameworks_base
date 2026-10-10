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

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.HashMap;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Tap to skip, hold to seek, without depending on a player's rewind/fast-forward commands. */
final class ScreenOffMediaKeyHandler {
    static final long HOLD_DELAY_MS = 500;
    static final long SEEK_INTERVAL_MS = 200;
    static final long SEEK_STEP_MS = 2000;
    private static final long SEEK_ACCELERATION_INTERVAL_MS = 4000;
    // Bound both seeking and the wake lock if a device loses its key-up event.
    static final long MAX_HOLD_MS = 60_000;

    private final Handler mHandler;
    private final PowerManager.WakeLock mEventWakeLock;
    private final PowerManager.WakeLock mHoldWakeLock;
    private final Supplier<MediaController> mControllerSupplier;
    private final Consumer<KeyEvent> mDispatchMediaKey;
    private final BooleanSupplier mEnabled;
    // Accessed only by intercept(), under its monitor. Keep consuming releases after screen wake.
    private final HashMap<Long, Long> mConsumedKeys = new HashMap<>();

    // All remaining state belongs to the policy handler thread.
    private KeyEvent mDownEvent;
    private MediaController mController;
    private MediaMetadata mMetadata;
    private long mQueueItemId;
    private long mPosition;
    private boolean mHolding;
    private final Runnable mSeek = this::seek;
    private final Runnable mCancel = this::cancel;

    ScreenOffMediaKeyHandler(Handler handler, PowerManager.WakeLock eventWakeLock,
            PowerManager.WakeLock holdWakeLock, Supplier<MediaController> controllerSupplier,
            Consumer<KeyEvent> dispatchMediaKey, BooleanSupplier enabled) {
        mHandler = handler;
        mEventWakeLock = eventWakeLock;
        mHoldWakeLock = holdWakeLock;
        mControllerSupplier = controllerSupplier;
        mDispatchMediaKey = dispatchMediaKey;
        mEnabled = enabled;
    }

    synchronized boolean intercept(KeyEvent event, boolean enabled, boolean mediaPlaying) {
        final int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) {
            return false;
        }
        final long key = ((long) event.getDeviceId() << 32) | code;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                && enabled) {
            mConsumedKeys.put(key, event.getDownTime());
            post(event, mediaPlaying);
            return true;
        }
        final Long downTime = mConsumedKeys.get(key);
        if (downTime != null && downTime == event.getDownTime()) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mConsumedKeys.remove(key);
                post(event, enabled);
            }
            return true;
        }
        return enabled;
    }

    private void post(KeyEvent event, boolean allowed) {
        final KeyEvent copy = new KeyEvent(event);
        mEventWakeLock.acquire();
        mHandler.post(() -> {
            try {
                handle(copy, allowed);
            } finally {
                mEventWakeLock.release();
            }
        });
    }

    private void handle(KeyEvent event, boolean allowed) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            cancel();
            if (!allowed || !mEnabled.getAsBoolean()) {
                return;
            }
            mDownEvent = event;
            mController = mControllerSupplier.get();
            mMetadata = mController == null ? null : mController.getMetadata();
            final PlaybackState state = mController == null ? null : mController.getPlaybackState();
            mQueueItemId = state == null
                    ? MediaSession.QueueItem.UNKNOWN_ID : state.getActiveQueueItemId();
            mHoldWakeLock.acquire(MAX_HOLD_MS + 1000);
            mHandler.postDelayed(mCancel, MAX_HOLD_MS);
            mHandler.postDelayed(mSeek, Math.max(0,
                    event.getDownTime() + HOLD_DELAY_MS - SystemClock.uptimeMillis()));
            return;
        }
        if (mDownEvent == null || event.getKeyCode() != mDownEvent.getKeyCode()
                || event.getDeviceId() != mDownEvent.getDeviceId()
                || event.getDownTime() != mDownEvent.getDownTime()) {
            return;
        }
        if (allowed && mEnabled.getAsBoolean() && !event.isCanceled() && !mHolding
                && event.getEventTime() - event.getDownTime() < HOLD_DELAY_MS
                && sameSessionAndTrack()) {
            final int code = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                    ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : KeyEvent.KEYCODE_MEDIA_NEXT;
            final KeyEvent down = new KeyEvent(event.getEventTime(), event.getEventTime(),
                    KeyEvent.ACTION_DOWN, code, 0);
            // Dispatch a complete click. Do not synthesize a long-press media-key repeat.
            mDispatchMediaKey.accept(down);
            mDispatchMediaKey.accept(KeyEvent.changeAction(down, KeyEvent.ACTION_UP));
        }
        cancel();
    }

    private boolean sameSessionAndTrack() {
        final MediaController current = mControllerSupplier.get();
        if (mController == null || current == null) {
            return mController == current;
        }
        if (!mController.getSessionToken().equals(current.getSessionToken())) {
            return false;
        }
        final PlaybackState state = mController.getPlaybackState();
        if (state == null || state.getActiveQueueItemId() != mQueueItemId) {
            return false;
        }
        final MediaMetadata metadata = mController.getMetadata();
        if (mMetadata == null || metadata == null) {
            return mMetadata == metadata;
        }
        return Objects.equals(mMetadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                        metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID))
                && Objects.equals(mMetadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                        metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
                && mMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                        == metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    private void seek() {
        if (mDownEvent == null) {
            return;
        }
        final boolean first = !mHolding;
        mHolding = true;
        if (!mEnabled.getAsBoolean() || mController == null || !sameSessionAndTrack()) {
            cancel();
            return;
        }
        final PlaybackState state = mController.getPlaybackState();
        if (state == null || (state.getState() != PlaybackState.STATE_PLAYING
                && (first || state.getState() != PlaybackState.STATE_BUFFERING))
                || (state.getActions() & PlaybackState.ACTION_SEEK_TO) == 0
                || state.getPosition() == PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
            cancel();
            return;
        }
        if (first) {
            mPosition = state.getPosition();
            if (state.getLastPositionUpdateTime() > 0) {
                mPosition += (long) (Math.max(0, SystemClock.elapsedRealtime()
                        - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
            }
        }
        // Maintain our own target: apps may publish playback-position updates slowly.
        final long heldFor = SystemClock.uptimeMillis() - mDownEvent.getDownTime();
        final long seekStep = heldFor >= 2 * SEEK_ACCELERATION_INTERVAL_MS ? 10_000
                : heldFor >= SEEK_ACCELERATION_INTERVAL_MS ? 5000 : SEEK_STEP_MS;
        final long step = mDownEvent.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT
                ? -seekStep : seekStep;
        mPosition = Math.max(0, mPosition + step);
        final long duration = mMetadata == null ? 0
                : mMetadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (duration > 0) {
            mPosition = Math.min(mPosition, duration - 1);
        }
        mController.getTransportControls().seekTo(mPosition);
        mHandler.postDelayed(mSeek, SEEK_INTERVAL_MS);
    }

    void cancelOnWake() {
        mHandler.post(mCancel);
    }

    private void cancel() {
        mHandler.removeCallbacks(mSeek);
        mHandler.removeCallbacks(mCancel);
        mDownEvent = null;
        mController = null;
        mMetadata = null;
        mHolding = false;
        if (mHoldWakeLock.isHeld()) {
            mHoldWakeLock.release();
        }
    }
}
