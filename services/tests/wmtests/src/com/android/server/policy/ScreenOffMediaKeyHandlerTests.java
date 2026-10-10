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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.test.TestLooper;
import android.view.KeyEvent;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;

/** atest WmTests:ScreenOffMediaKeyHandlerTests */
@SmallTest
public class ScreenOffMediaKeyHandlerTests {
    private final TestLooper mLooper = new TestLooper();
    private final MediaController mController = mock(MediaController.class);
    private final MediaController.TransportControls mControls =
            mock(MediaController.TransportControls.class);
    private final PowerManager.WakeLock mEventWakeLock = mock(PowerManager.WakeLock.class);
    private final PowerManager.WakeLock mHoldWakeLock = mock(PowerManager.WakeLock.class);
    private final ArrayList<KeyEvent> mClicks = new ArrayList<>();
    private MediaController mCurrentController = mController;
    private boolean mEnabled = true;
    private boolean mHeld;
    private ScreenOffMediaKeyHandler mKeys;
    private long mDownTime;
    private int mCode;

    @Before
    public void setUp() {
        when(mController.getSessionToken()).thenReturn(mock(MediaSession.Token.class));
        when(mController.getTransportControls()).thenReturn(mControls);
        when(mController.getMetadata()).thenReturn(metadata("track", 60_000));
        state(PlaybackState.STATE_PLAYING, 10_000, PlaybackState.ACTION_SEEK_TO);
        when(mHoldWakeLock.isHeld()).thenAnswer(invocation -> mHeld);
        org.mockito.Mockito.doAnswer(invocation -> {
            mHeld = true;
            return null;
        }).when(mHoldWakeLock).acquire(ScreenOffMediaKeyHandler.MAX_HOLD_MS + 1000);
        org.mockito.Mockito.doAnswer(invocation -> {
            mHeld = false;
            return null;
        }).when(mHoldWakeLock).release();
        mKeys = new ScreenOffMediaKeyHandler(new Handler(mLooper.getLooper()),
                mEventWakeLock, mHoldWakeLock, () -> mCurrentController, mClicks::add,
                () -> mEnabled);
    }

    @Test
    public void shortPressSkipsOnlyOnRelease() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue(mClicks.isEmpty());
        release(100);
        assertEquals(2, mClicks.size());
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, mClicks.get(0).getKeyCode());
        assertEquals(KeyEvent.ACTION_DOWN, mClicks.get(0).getAction());
        assertEquals(KeyEvent.ACTION_UP, mClicks.get(1).getAction());
        verifyZeroInteractions(mControls);
        assertFalse(mHeld);
    }

    @Test
    public void leftTapSkipsPrevious() {
        press(KeyEvent.KEYCODE_DPAD_LEFT);
        release(100);
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, mClicks.get(0).getKeyCode());
    }

    @Test
    public void holdSeeksRepeatedlyDespiteStalePositionAndStopsOnRelease() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        advance(500);
        verify(mControls).seekTo(12_000);
        advance(200);
        verify(mControls).seekTo(14_000);
        release(800);
        advance(200);
        verify(mControls, never()).seekTo(16_000);
        assertTrue(mClicks.isEmpty());
        assertFalse(mHeld);
    }

    @Test
    public void rewindClampsToStart() {
        state(PlaybackState.STATE_PLAYING, 1000, PlaybackState.ACTION_SEEK_TO);
        press(KeyEvent.KEYCODE_DPAD_LEFT);
        advance(500);
        verify(mControls).seekTo(0);
    }

    @Test
    public void forwardClampsBeforeEnd() {
        state(PlaybackState.STATE_PLAYING, 59_000, PlaybackState.ACTION_SEEK_TO);
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        advance(500);
        verify(mControls).seekTo(59_999);
    }

    @Test
    public void unsupportedHoldDoesNotSkip() {
        state(PlaybackState.STATE_PLAYING, 10_000, PlaybackState.ACTION_SKIP_TO_NEXT);
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        advance(500);
        release(700);
        verifyZeroInteractions(mControls);
        assertTrue(mClicks.isEmpty());
        assertFalse(mHeld);
    }

    @Test
    public void wakeCancelsAndConsumesRelease() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        mEnabled = false;
        mKeys.cancelOnWake();
        mLooper.dispatchAll();
        release(100);
        advance(500);
        verifyZeroInteractions(mControls);
        assertTrue(mClicks.isEmpty());
        assertFalse(mHeld);
    }

    @Test
    public void trackChangeStopsSeeking() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        advance(500);
        when(mController.getMetadata()).thenReturn(metadata("other", 60_000));
        advance(200);
        verify(mControls, never()).seekTo(14_000);
        release(800);
        assertTrue(mClicks.isEmpty());
        assertFalse(mHeld);
    }

    @Test
    public void sessionChangeCancelsTap() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        mCurrentController = mock(MediaController.class);
        when(mCurrentController.getSessionToken()).thenReturn(mock(MediaSession.Token.class));
        release(100);
        assertTrue(mClicks.isEmpty());
    }

    @Test
    public void bufferingAfterSeekDoesNotStopHold() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        advance(500);
        state(PlaybackState.STATE_BUFFERING, 10_000, PlaybackState.ACTION_SEEK_TO);
        advance(200);
        verify(mControls).seekTo(14_000);
    }

    @Test
    public void pauseStopsSeeking() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        advance(500);
        state(PlaybackState.STATE_PAUSED, 12_000, PlaybackState.ACTION_SEEK_TO);
        advance(200);
        verify(mControls, never()).seekTo(14_000);
        assertFalse(mHeld);
    }

    @Test
    public void missingReleaseTimesOut() {
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        advance(ScreenOffMediaKeyHandler.MAX_HOLD_MS);
        assertFalse(mHeld);
        assertTrue(mClicks.isEmpty());
    }

    @Test
    public void screenOnPressIsNotConsumed() {
        assertFalse(mKeys.intercept(new KeyEvent(KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT), false, true));
        verifyZeroInteractions(mEventWakeLock);
    }

    private void press(int code) {
        mCode = code;
        mDownTime = SystemClock.uptimeMillis();
        assertTrue(mKeys.intercept(new KeyEvent(mDownTime, mDownTime,
                KeyEvent.ACTION_DOWN, code, 0), true, true));
        mLooper.dispatchAll();
    }

    private void release(long duration) {
        assertTrue(mKeys.intercept(new KeyEvent(mDownTime, mDownTime + duration,
                KeyEvent.ACTION_UP, mCode, 0), mEnabled, true));
        mLooper.dispatchAll();
    }

    private void advance(long duration) {
        mLooper.moveTimeForward(duration);
        mLooper.dispatchAll();
    }

    private void state(int state, long position, long actions) {
        when(mController.getPlaybackState()).thenReturn(new PlaybackState.Builder()
                .setState(state, position, 1f, 0).setActions(actions).build());
    }

    private static MediaMetadata metadata(String id, long duration) {
        return new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, id)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration).build();
    }
}
