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

package com.android.systemui.globalactions;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import android.content.Context;
import android.os.SystemClock;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class GlobalActionsItemInteractionTest extends SysuiTestCase {
    private View mItem;
    private View.OnClickListener mClickListener;

    @Before
    public void setUp() {
        mItem = createItem();
        mClickListener = mock(View.OnClickListener.class);
        mItem.setOnClickListener(mClickListener);
    }

    @Test
    public void singleTapOnUnfocusedItemClicks() {
        assertThat(mItem.isFocused()).isFalse();

        dispatchTouch(MotionEvent.ACTION_DOWN);
        verifyNoInteractions(mClickListener);
        dispatchTouch(MotionEvent.ACTION_UP);

        verify(mClickListener).onClick(mItem);
    }

    @Test
    public void cancelledTouchDoesNotClick() {
        dispatchTouch(MotionEvent.ACTION_DOWN);
        dispatchTouch(MotionEvent.ACTION_CANCEL);

        verifyNoInteractions(mClickListener);
    }

    @Test
    public void disabledItemDoesNotClickOrTakeFocus() {
        mItem.setEnabled(false);

        dispatchTouch(MotionEvent.ACTION_DOWN);
        dispatchTouch(MotionEvent.ACTION_UP);

        verifyNoInteractions(mClickListener);
        assertThat(mItem.isFocused()).isFalse();
    }

    @Test
    public void itemCanTakeInitialFocusAndHandleDpadCenterInTouchMode() {
        assertThat(mItem.requestFocus()).isTrue();

        mItem.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        verifyNoInteractions(mClickListener);
        mItem.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));

        verify(mClickListener).onClick(mItem);
    }

    @Test
    public void dpadFocusCanMoveBetweenItemsInTouchMode() {
        LinearLayout row = new LinearLayout(mContext) {
            @Override
            public boolean isInTouchMode() {
                return true;
            }
        };
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        View nextItem = createItem();
        row.addView(mItem, new LinearLayout.LayoutParams(100, 100));
        row.addView(nextItem, new LinearLayout.LayoutParams(100, 100));
        row.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        row.layout(0, 0, 200, 100);
        assertThat(mItem.requestFocus()).isTrue();

        View nextFocus = FocusFinder.getInstance().findNextFocus(row, mItem, View.FOCUS_RIGHT);

        assertThat(nextFocus).isSameInstanceAs(nextItem);
        assertThat(nextFocus.requestFocus(View.FOCUS_RIGHT)).isTrue();
        assertThat(nextItem.isFocused()).isTrue();
        assertThat(mItem.isFocused()).isFalse();
    }

    private View createItem() {
        View item = new TestItem(mContext);
        GlobalActionsDialogLite.prepareItemViewForDpadNavigation(item);
        item.layout(0, 0, 100, 100);
        return item;
    }

    private static class TestItem extends GlobalActionsItem {
        TestItem(Context context) {
            super(context);
        }

        @Override
        public boolean isInTouchMode() {
            // The menu can open while the window is still in touch mode.
            return true;
        }

        @Override
        public boolean post(Runnable action) {
            // These views have no window handler; use View's synchronous click fallback.
            return false;
        }
    }

    private void dispatchTouch(int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 50f, 50f, 0);
        try {
            mItem.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }
}
