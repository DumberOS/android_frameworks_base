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

package com.android.systemui.statusbar.phone;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Handler;
import android.telephony.ims.ImsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsRegistrationAttributes;
import android.telephony.ims.ImsStateCallback;
import android.telephony.ims.RegistrationManager;
import android.telephony.ims.feature.MmTelFeature.MmTelCapabilities;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.systemui.res.R;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/** Shows a status bar icon while MMTEL voice is registered over IWLAN. */
final class VoWifiStatusBarIconController {
    private static final String TAG = "VoWifiStatusBarIcon";
    private static final long CALLBACK_RETRY_MILLIS = 5_000;

    private final Context mContext;
    private final StatusBarIconController mIconController;
    private final Executor mMainExecutor;
    private final Handler mHandler;
    private final String mSlotVoWifi;
    private final SubscriptionManager mSubscriptionManager;
    private final ImsManager mImsManager;
    private final ArrayMap<Integer, SubscriptionState> mSubscriptionStates = new ArrayMap<>();

    private final SubscriptionManager.OnSubscriptionsChangedListener
            mSubscriptionsChangedListener =
            new SubscriptionManager.OnSubscriptionsChangedListener() {
                @Override
                public void onSubscriptionsChanged() {
                    updateSubscriptions();
                }
            };

    private boolean mVisible;

    VoWifiStatusBarIconController(Context context, StatusBarIconController iconController,
            Executor mainExecutor, Handler handler) {
        mContext = context;
        mIconController = iconController;
        mMainExecutor = mainExecutor;
        mHandler = handler;
        mSlotVoWifi = context.getString(com.android.internal.R.string.status_bar_vowifi);
        mSubscriptionManager = context.getSystemService(SubscriptionManager.class);
        mImsManager = context.getSystemService(ImsManager.class);
    }

    void init() {
        mIconController.setIcon(mSlotVoWifi, R.drawable.stat_sys_vowifi,
                mContext.getString(R.string.accessibility_status_bar_vowifi));
        mIconController.setIconVisibility(mSlotVoWifi, false);
        if (mSubscriptionManager == null || mImsManager == null) {
            Log.d(TAG, "IMS telephony services are unavailable");
            return;
        }
        mSubscriptionManager.addOnSubscriptionsChangedListener(
                mMainExecutor, mSubscriptionsChangedListener);
        updateSubscriptions();
    }

    private void updateSubscriptions() {
        List<SubscriptionInfo> subscriptions;
        try {
            subscriptions = mSubscriptionManager.getActiveSubscriptionInfoList();
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to query active subscriptions", e);
            return;
        }

        Set<Integer> activeSubIds = new ArraySet<>();
        if (subscriptions != null) {
            for (SubscriptionInfo subscription : subscriptions) {
                activeSubIds.add(subscription.getSubscriptionId());
            }
        }

        for (int index = mSubscriptionStates.size() - 1; index >= 0; index--) {
            int subId = mSubscriptionStates.keyAt(index);
            if (!activeSubIds.contains(subId)) {
                mSubscriptionStates.remove(subId).stop();
            }
        }

        for (int subId : activeSubIds) {
            if (mSubscriptionStates.containsKey(subId)) {
                continue;
            }
            try {
                SubscriptionState state = new SubscriptionState(
                        subId, mImsManager.getImsMmTelManager(subId));
                mSubscriptionStates.put(subId, state);
                state.start();
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to monitor IMS for subId=" + subId, e);
            }
        }
        updateIconVisibility();
    }

    private void updateIconVisibility() {
        boolean visible = false;
        for (SubscriptionState state : mSubscriptionStates.values()) {
            visible |= state.isVoWifiReady();
        }
        if (mVisible == visible) {
            return;
        }
        mVisible = visible;
        Log.i(TAG, "VoWiFi icon visible=" + visible);
        mIconController.setIconVisibility(mSlotVoWifi, visible);
    }

    private final class SubscriptionState {
        private final int mSubId;
        private final ImsMmTelManager mMmTelManager;

        private boolean mStopped;
        private boolean mStateCallbackRegistered;
        private boolean mRegistrationCallbackRegistered;
        private boolean mCapabilityCallbackRegistered;
        private boolean mRegisteredOverIwlan;
        private boolean mVoiceCapable;

        private final Runnable mRetryStateCallback = this::registerStateCallback;
        private final Runnable mRetryMmTelCallbacks = this::registerMmTelCallbacks;

        private final ImsStateCallback mImsStateCallback = new ImsStateCallback() {
            @Override
            public void onAvailable() {
                registerMmTelCallbacks();
            }

            @Override
            public void onUnavailable(@DisconnectedReason int reason) {
                unregisterMmTelCallbacks();
                clearReadyState();
                if (reason == REASON_SUBSCRIPTION_INACTIVE) {
                    mStateCallbackRegistered = false;
                }
            }

            @Override
            public void onError() {
                mStateCallbackRegistered = false;
                unregisterMmTelCallbacks();
                clearReadyState();
                scheduleStateCallbackRetry();
            }
        };

        private final RegistrationManager.RegistrationCallback mRegistrationCallback =
                new RegistrationManager.RegistrationCallback() {
                    @Override
                    public void onRegistered(@NonNull ImsRegistrationAttributes attributes) {
                        mRegisteredOverIwlan = attributes.getRegistrationTechnology()
                                == ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN;
                        updateIconVisibility();
                    }

                    @Override
                    public void onRegistering(@NonNull ImsRegistrationAttributes attributes) {
                        mRegisteredOverIwlan = false;
                        updateIconVisibility();
                    }

                    @Override
                    public void onUnregistered(@NonNull ImsReasonInfo info) {
                        mRegisteredOverIwlan = false;
                        updateIconVisibility();
                    }
                };

        private final ImsMmTelManager.CapabilityCallback mCapabilityCallback =
                new ImsMmTelManager.CapabilityCallback() {
                    @Override
                    public void onCapabilitiesStatusChanged(
                            @NonNull MmTelCapabilities capabilities) {
                        mVoiceCapable = capabilities.isCapable(
                                MmTelCapabilities.CAPABILITY_TYPE_VOICE);
                        updateIconVisibility();
                    }
                };

        SubscriptionState(int subId, ImsMmTelManager mmTelManager) {
            mSubId = subId;
            mMmTelManager = mmTelManager;
        }

        void start() {
            registerStateCallback();
        }

        void stop() {
            mStopped = true;
            mHandler.removeCallbacks(mRetryStateCallback);
            mHandler.removeCallbacks(mRetryMmTelCallbacks);
            unregisterMmTelCallbacks();
            if (mStateCallbackRegistered) {
                try {
                    mMmTelManager.unregisterImsStateCallback(mImsStateCallback);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Unable to unregister IMS state for subId=" + mSubId, e);
                }
                mStateCallbackRegistered = false;
            }
        }

        boolean isVoWifiReady() {
            return mRegisteredOverIwlan && mVoiceCapable;
        }

        private void registerStateCallback() {
            if (mStopped || mStateCallbackRegistered) {
                return;
            }
            try {
                mMmTelManager.registerImsStateCallback(mMainExecutor, mImsStateCallback);
                mStateCallbackRegistered = true;
            } catch (ImsException | RuntimeException e) {
                Log.w(TAG, "Unable to register IMS state for subId=" + mSubId, e);
                scheduleStateCallbackRetry();
            }
        }

        private void scheduleStateCallbackRetry() {
            if (mStopped) {
                return;
            }
            mHandler.removeCallbacks(mRetryStateCallback);
            mHandler.postDelayed(mRetryStateCallback, CALLBACK_RETRY_MILLIS);
        }

        private void registerMmTelCallbacks() {
            if (mStopped) {
                return;
            }
            if (!mRegistrationCallbackRegistered) {
                try {
                    mMmTelManager.registerImsRegistrationCallback(
                            mMainExecutor, mRegistrationCallback);
                    mRegistrationCallbackRegistered = true;
                } catch (ImsException | RuntimeException e) {
                    Log.w(TAG, "Unable to register IMS registration for subId=" + mSubId, e);
                }
            }
            if (!mCapabilityCallbackRegistered) {
                try {
                    mMmTelManager.registerMmTelCapabilityCallback(
                            mMainExecutor, mCapabilityCallback);
                    mCapabilityCallbackRegistered = true;
                } catch (ImsException | RuntimeException e) {
                    Log.w(TAG, "Unable to register IMS capabilities for subId=" + mSubId, e);
                }
            }
            if (!mRegistrationCallbackRegistered || !mCapabilityCallbackRegistered) {
                mHandler.removeCallbacks(mRetryMmTelCallbacks);
                mHandler.postDelayed(mRetryMmTelCallbacks, CALLBACK_RETRY_MILLIS);
            }
        }

        private void unregisterMmTelCallbacks() {
            mHandler.removeCallbacks(mRetryMmTelCallbacks);
            if (mRegistrationCallbackRegistered) {
                try {
                    mMmTelManager.unregisterImsRegistrationCallback(mRegistrationCallback);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Unable to unregister IMS registration for subId=" + mSubId, e);
                }
                mRegistrationCallbackRegistered = false;
            }
            if (mCapabilityCallbackRegistered) {
                try {
                    mMmTelManager.unregisterMmTelCapabilityCallback(mCapabilityCallback);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Unable to unregister IMS capabilities for subId=" + mSubId, e);
                }
                mCapabilityCallbackRegistered = false;
            }
        }

        private void clearReadyState() {
            mRegisteredOverIwlan = false;
            mVoiceCapable = false;
            updateIconVisibility();
        }
    }
}
