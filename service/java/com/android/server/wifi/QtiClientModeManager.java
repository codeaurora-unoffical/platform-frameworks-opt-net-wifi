/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.ConnectivityManager;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.ScanResult;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.LinkProperties;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.telephony.AccessNetworkConstants;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.SubscriptionInfo;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.RegistrationManager;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.text.TextUtils;
import android.util.Log;
import android.os.Handler;
import android.os.WorkSource;

import android.net.wifi.WifiConfiguration;
import com.android.internal.util.IState;
import com.android.internal.util.Preconditions;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.AsyncChannel;
import com.android.server.wifi.WifiNative.InterfaceCallback;
import com.android.server.wifi.util.WifiAsyncChannel;
import com.android.server.wifi.util.WifiHandler;

import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
/**
 * Manager WiFi in Client Mode where we connect to configured networks.
 */
public class QtiClientModeManager implements ActiveModeManager {
    private static final String TAG = "QtiWifiClientModeManager";

    private final ClientModeStateMachine mStateMachine;

    private final Context mContext;
    private final Clock mClock;
    private final WifiNative mWifiNative;

    private final WifiInjector mWifiInjector;
    private final SarManager mSarManager;
    private final WakeupController mWakeupController;
    private final Listener mListener;

    private String mClientInterfaceName;
    private boolean mIfaceIsUp = false;
    private @Role int mRole = ROLE_UNSPECIFIED;
    private DeferStopHandler mDeferStopHandler;
    private int mTargetRole = ROLE_UNSPECIFIED;
    private int mActiveSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;

    private final QtiClientModeImpl mQtiClientModeImpl;
    private final int mStaId;
    /**
     * Asynchronous channel to QtiClientModeImpl
     */
    AsyncChannel mQtiClientModeImplChannel;

    QtiClientModeManager(Context context, @NonNull Looper looper, Clock clock, WifiNative wifiNative,
            Listener listener, WifiInjector wifiInjector, SarManager sarManager,
            WakeupController wakeupController, int staId, WifiConfigManager wifiConfigManager) {
        mContext = context;
        mClock = clock;
        mWifiNative = wifiNative;
        mSarManager = sarManager;
        mWakeupController = wakeupController;
        mWifiInjector = wifiInjector;
        mStaId = staId;
        mListener = listener;
        mQtiClientModeImpl = wifiInjector.makeQtiClientModeImpl(mListener, wifiConfigManager);
        mStateMachine = new ClientModeStateMachine(looper);
        mDeferStopHandler = new DeferStopHandler(TAG, looper);
    }

    public QtiClientModeImpl getClientModeImpl() {
        return mQtiClientModeImpl;
    }

    public int getStaId() {
        return mStaId;
    }

    public AsyncChannel getClientImplChannel() {
        return mQtiClientModeImplChannel;
    }

    /**
     * Start client mode.
     */
    @Override
    public void start() {
        mTargetRole = ROLE_CLIENT_SCAN_ONLY;
        mQtiClientModeImpl.enableVerboseLogging(mWifiInjector.getVerboseLogging());
        mQtiClientModeImpl.start();
        mStateMachine.sendMessage(ClientModeStateMachine.CMD_START);
    }

    /**
     * Disconnect from any currently connected networks and stop client mode.
     */
    @Override
    public void stop() {
        Log.d(TAG, " currentstate: " + getCurrentStateName());
        mTargetRole = ROLE_UNSPECIFIED;
        if (mIfaceIsUp) {
            updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                    WifiManager.WIFI_STATE_ENABLED);
        } else {
            updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                    WifiManager.WIFI_STATE_ENABLING);
        }
        mDeferStopHandler.start(getWifiOffDeferringTimeMs());
        mQtiClientModeImpl.quit();
    }

    @Override
    public boolean isStopping() {
        return mTargetRole == ROLE_UNSPECIFIED && mRole != ROLE_UNSPECIFIED;
    }
    private class DeferStopHandler extends WifiHandler {
        private boolean mIsDeferring = false;
        private ImsMmTelManager mImsMmTelManager = null;
        private Looper mLooper = null;
        private final Runnable mRunnable = () -> continueToStopWifi();
        private int mMaximumDeferringTimeMillis = 0;
        private long mDeferringStartTimeMillis = 0;

        private static final int QTI_DELAY_DISCONNECT_ON_NETWORK_LOST_MS = 1000;
        private NetworkRequest mImsRequest = null;
        private ConnectivityManager mConnectivityManager = null;

        private RegistrationManager.RegistrationCallback mImsRegistrationCallback =
                new RegistrationManager.RegistrationCallback() {
                    @Override
                    public void onRegistered(int imsRadioTech) {
                        Log.d(TAG, "on IMS registered on type " + imsRadioTech);
                        if (!mIsDeferring) return;

                        if (imsRadioTech != AccessNetworkConstants.TRANSPORT_TYPE_WLAN) {
                            continueToStopWifi();
                        }
                    }

                    @Override
                    public void onUnregistered(ImsReasonInfo imsReasonInfo) {
                        Log.d(TAG, "on IMS unregistered");
                        // Wait for onLost in NetworkCallback
                    }
                };

        private NetworkCallback mImsNetworkCallback = new NetworkCallback() {
            private int countRegIMS = 0;
            @Override
            public void onAvailable(Network network) {
                Log.d(TAG, "IMS network available id: " + network);
                countRegIMS++;
            }

            @Override
            public void onLost(Network network) {
                Log.d(TAG, "IMS network lost: " + network);
                countRegIMS--;
                // Add additional delay of 1 sec after onLost() indication as IMS PDN down
                // at modem takes additional 500ms+ of delay.
                // TODO: this should be fixed.
                if (mIsDeferring && (countRegIMS == 0)
                        && !postDelayed(mRunnable, QTI_DELAY_DISCONNECT_ON_NETWORK_LOST_MS))
                    continueToStopWifi();
            }
        };

        DeferStopHandler(String tag, Looper looper) {
            super(tag, looper);
            mLooper = looper;
        }

        public void start(int delayMs) {
            if (mIsDeferring) return;

            mMaximumDeferringTimeMillis = delayMs;
            mDeferringStartTimeMillis = mClock.getElapsedSinceBootMillis();
            // Most cases don't need delay, check it first to avoid unnecessary work.
            if (delayMs == 0) {
                continueToStopWifi();
                return;
            }

            mImsMmTelManager = ImsMmTelManager.createForSubscriptionId(mActiveSubId);
            if (mImsMmTelManager == null || !postDelayed(mRunnable, delayMs)) {
                // if no delay or failed to add runnable, stop Wifi immediately.
                continueToStopWifi();
                return;
            }

            mIsDeferring = true;
            Log.d(TAG, "Start DeferWifiOff handler with deferring time "
                    + delayMs + " ms. for subId: " + mActiveSubId);

            try {
                mImsMmTelManager.registerImsRegistrationCallback(
                        new HandlerExecutor(new Handler(mLooper)),
                        mImsRegistrationCallback);
            } catch (RuntimeException | ImsException e) {
                Log.e(TAG, "registerImsRegistrationCallback failed", e);
                continueToStopWifi();
                return;
            }

            mImsRequest = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();

            mConnectivityManager = (ConnectivityManager)mContext.getSystemService
                                   (Context.CONNECTIVITY_SERVICE);

            mConnectivityManager.registerNetworkCallback(mImsRequest, mImsNetworkCallback,
                                                         new Handler(mLooper));
        }

        private void continueToStopWifi() {
            Log.d(TAG, "The target role " + mTargetRole);

            int deferringDurationMillis =
                    (int) (mClock.getElapsedSinceBootMillis() - mDeferringStartTimeMillis);
            boolean isTimedOut = mMaximumDeferringTimeMillis > 0
                    && deferringDurationMillis >= mMaximumDeferringTimeMillis;
            if (mTargetRole == ROLE_UNSPECIFIED) {
                Log.d(TAG, "Continue to stop wifi");
                mStateMachine.quitNow();
            } else if (mTargetRole == ROLE_CLIENT_SCAN_ONLY) {
                mStateMachine.sendMessage(
                            ClientModeStateMachine.CMD_SWITCH_TO_SCAN_ONLY_MODE_CONTINUE);
            } else {
                updateConnectModeState(WifiManager.WIFI_STATE_ENABLED,
                        WifiManager.WIFI_STATE_DISABLING);
            }

            if (!mIsDeferring) return;

            Log.d(TAG, "Stop DeferWifiOff handler.");
            removeCallbacks(mRunnable);
            if (mImsMmTelManager != null) {
                try {
                    mImsMmTelManager.unregisterImsRegistrationCallback(mImsRegistrationCallback);
                } catch (RuntimeException e) {
                    Log.e(TAG, "unregisterImsRegistrationCallback failed", e);
                }
            }

            if (mConnectivityManager != null) {
                mConnectivityManager.unregisterNetworkCallback(mImsNetworkCallback);
            }

            mIsDeferring = false;
        }
    }

    /**
     * Get deferring time before turning off WiFi.
     */
    private int getWifiOffDeferringTimeMs() {
        SubscriptionManager subscriptionManager = (SubscriptionManager) mContext.getSystemService(
                Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        if (subscriptionManager == null) {
            return 0;
        }

        List<SubscriptionInfo> subInfoList = subscriptionManager.getActiveSubscriptionInfoList();
        if (subInfoList == null) {
            return 0;
        }

        // Get the delay for first active subscription latched on IWLAN.
        int delay = 0;
        for (SubscriptionInfo subInfo : subInfoList) {
            delay = getWifiOffDeferringTimeMs(subInfo.getSubscriptionId());
            if (delay > 0) {
                mActiveSubId = subInfo.getSubscriptionId();
                break;
            }
        }
        return delay;
    }

    private int getWifiOffDeferringTimeMs(int subId) {
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return 0;
        }

        ImsMmTelManager imsMmTelManager = ImsMmTelManager.createForSubscriptionId(subId);
        // If no wifi calling, no delay
        if (!imsMmTelManager.isAvailable(
                    MmTelFeature.MmTelCapabilities.CAPABILITY_TYPE_VOICE,
                    ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN)) {
            return 0;
        }

        TelephonyManager defaultVoiceTelephonyManager =
                mContext.getSystemService(TelephonyManager.class)
                        .createForSubscriptionId(subId);
        // if LTE is available, no delay needed as IMS will be registered over LTE
        if (defaultVoiceTelephonyManager.getVoiceNetworkType()
                == TelephonyManager.NETWORK_TYPE_LTE) {
            return 0;
        }

        CarrierConfigManager configManager =
                (CarrierConfigManager) mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE);
        PersistableBundle config = configManager.getConfigForSubId(subId);
        return (config != null)
                ? config.getInt(CarrierConfigManager.Ims.KEY_WIFI_OFF_DEFERRING_TIME_MILLIS_INT)
                : 0;
    }

    @Override
    public @Role int getRole() {
        return mRole;
    }

    @Override
    public void setRole(@Role int role) {
        Preconditions.checkState(CLIENT_ROLES.contains(role));
        if (role == ROLE_CLIENT_SCAN_ONLY) {
            mTargetRole = role;
            // Switch client mode manager to scan only mode.
            mStateMachine.sendMessage(ClientModeStateMachine.CMD_SWITCH_TO_SCAN_ONLY_MODE);
        } else if (CLIENT_CONNECTIVITY_ROLES.contains(role)) {
            mTargetRole = role;
            // Switch client mode manager to connect mode.
            mStateMachine.sendMessage(ClientModeStateMachine.CMD_SWITCH_TO_CONNECT_MODE, role);
        }
    }

    /**
     * Dump info about this ClientMode manager.
     */
    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("--Dump of QtiClientModeManager--");

        pw.println("current StateMachine mode: " + getCurrentStateName());
        pw.println("mRole: " + mRole);
        pw.println("mTargetRole: " + mTargetRole);
        pw.println("mClientInterfaceName: " + mClientInterfaceName);
        pw.println("mIfaceIsUp: " + mIfaceIsUp);
        mStateMachine.dump(fd, pw, args);
	}
    /**
     * Listener for ClientMode state changes.
     */
    public interface Listener {
        /**
         * Invoke when wifi state changes.
         * @param state new wifi state
         */
        void onStateChanged(int staId, int state);

        /**
         * Invoke when RSSI changes.
         * @param rssi new RSSI value.
         */
        void onRssiChanged(int staId, int rssi);

        /**
         * Invoke when Link configuration changes.
         * @param linkProperties Link Property object.
         */
        void onLinkConfigurationChanged(int staId, LinkProperties lp);

        /**
         * Invoke when network state changes.
         * @param networkInfo network info corresponding to bssid.
         */
        void onNetworkStateChanged(int staId, NetworkInfo netInfo);
    }

    private String getCurrentStateName() {
        IState currentState = mStateMachine.getCurrentState();

        if (currentState != null) {
            return currentState.getName();
        }

        return "StateMachine not active";
    }

    /**
     * Update Wifi state and send the broadcast.
     * @param newState new Wifi state
     * @param currentState current wifi state
     */
    private void updateConnectModeState(int newState, int currentState) {
        mListener.onStateChanged(mStaId, newState);
        mQtiClientModeImpl.setWifiStateForApiCalls(newState);
    }

    private class ClientModeStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_SWITCH_TO_SCAN_ONLY_MODE = 1;
        public static final int CMD_SWITCH_TO_CONNECT_MODE = 2;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;
        public static final int CMD_INTERFACE_DESTROYED = 4;
        public static final int CMD_INTERFACE_DOWN = 5;
        public static final int CMD_SWITCH_TO_SCAN_ONLY_MODE_CONTINUE = 6;
        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();
        private final State mScanOnlyModeState = new ScanOnlyModeState();
        private final State mConnectModeState = new ConnectModeState();

        private final InterfaceCallback mWifiNativeInterfaceCallback = new InterfaceCallback() {
            @Override
            public void onDestroyed(String ifaceName) {
                if (mClientInterfaceName != null && mClientInterfaceName.equals(ifaceName)) {
                    Log.d(TAG, "STA iface " + ifaceName + " was destroyed, stopping client mode");

                    // we must immediately clean up state in ClientModeImpl to unregister
                    // all client mode related objects
                    // Note: onDestroyed is only called from the main Wifi thread
                    mQtiClientModeImpl.handleIfaceDestroyed();

                    sendMessage(CMD_INTERFACE_DESTROYED);
                }
            }

            @Override
            public void onUp(String ifaceName) {
                if (mClientInterfaceName != null && mClientInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 1);
                }
            }

            @Override
            public void onDown(String ifaceName) {
                if (mClientInterfaceName != null && mClientInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 0);
                }
            }
        };

        ClientModeStateMachine(Looper looper) {
            super(TAG, looper);

            // CHECKSTYLE:OFF IndentationCheck
            addState(mIdleState);
            addState(mStartedState);
                addState(mScanOnlyModeState, mStartedState);
                addState(mConnectModeState, mStartedState);
            // CHECKSTYLE:ON IndentationCheck

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {
            @Override
            public void enter() {
                Log.d(TAG, "entering IdleState");
                mClientInterfaceName = null;
                mIfaceIsUp = false;
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_START:
                        // Always start in scan mode first.
                        mClientInterfaceName =
                                mWifiNative.setupInterfaceForClientInScanMode(
                                mWifiNativeInterfaceCallback);
                        if (TextUtils.isEmpty(mClientInterfaceName)) {
                            Log.e(TAG, "Failed to create ClientInterface. Sit in Idle");
                            break;
                        }
                        transitionTo(mScanOnlyModeState);
                        break;
                    default:
                        Log.d(TAG, "received an invalid message: " + message);
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        private class StartedState extends State {

            private void onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return;  // no change
                }
                mIfaceIsUp = isUp;
                if (!isUp) {
                    // if the interface goes down we should exit and go back to idle state.
                    Log.d(TAG, "interface down!");
                    mStateMachine.sendMessage(CMD_INTERFACE_DOWN);
                }
            }

            @Override
            public void enter() {
                Log.d(TAG, "entering StartedState");
                mIfaceIsUp = false;
                onUpChanged(mWifiNative.isInterfaceUp(mClientInterfaceName));
            }

            @Override
            public boolean processMessage(Message message) {
                switch(message.what) {
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_SWITCH_TO_CONNECT_MODE:
                        mRole = message.arg1; // could be any one of possible connect mode roles.
                        updateConnectModeState(WifiManager.WIFI_STATE_ENABLING,
                                WifiManager.WIFI_STATE_DISABLED);
                        if (!mWifiNative.switchClientInterfaceToConnectivityMode(
                                mClientInterfaceName)) {
                            updateConnectModeState(WifiManager.WIFI_STATE_UNKNOWN,
                                    WifiManager.WIFI_STATE_ENABLING);
                            updateConnectModeState(WifiManager.WIFI_STATE_DISABLED,
                                    WifiManager.WIFI_STATE_UNKNOWN);
                            break;
                        }
                        transitionTo(mConnectModeState);
                        break;
                    case CMD_SWITCH_TO_SCAN_ONLY_MODE:
                        updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_ENABLED);
                        mDeferStopHandler.start(getWifiOffDeferringTimeMs());
                        break;
                    case CMD_SWITCH_TO_SCAN_ONLY_MODE_CONTINUE:
                        transitionTo(mScanOnlyModeState);
                        break;
                    case CMD_INTERFACE_DOWN:
                        Log.e(TAG, "Detected an interface down, reporting failure to "
                                + "SelfRecovery");
                        transitionTo(mIdleState);
                        break;
                    case CMD_INTERFACE_STATUS_CHANGED:
                        boolean isUp = message.arg1 == 1;
                        onUpChanged(isUp);
                        break;
                    case CMD_INTERFACE_DESTROYED:
                        Log.d(TAG, "interface destroyed - client mode stopping");
                        mClientInterfaceName = null;
                        transitionTo(mIdleState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            /**
             * Clean up state, unregister listeners and update wifi state.
             */
            @Override
            public void exit() {
                mQtiClientModeImpl.setOperationalMode(ClientModeImpl.DISABLED_MODE, null);

                if (mClientInterfaceName != null) {
                    mWifiNative.teardownInterface(mClientInterfaceName);
                    mClientInterfaceName = null;
                    mIfaceIsUp = false;
                }

                // once we leave started, nothing else to do...  stop the state machine
                mRole = ROLE_UNSPECIFIED;
                mStateMachine.quitNow();
                mQtiClientModeImpl.quit();
            }
        }

        private class ScanOnlyModeState extends State {
            @Override
            public void enter() {
                Log.d(TAG, "entering ScanOnlyModeState");
                mQtiClientModeImpl.setOperationalMode(ClientModeImpl.SCAN_ONLY_MODE,
                        mClientInterfaceName);
                mRole = ROLE_CLIENT_SCAN_ONLY;

                // Inform sar manager that scan only is being enabled
                mSarManager.setScanOnlyWifiState(WifiManager.WIFI_STATE_ENABLED);
                mWakeupController.start();
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_SWITCH_TO_SCAN_ONLY_MODE:
                        // Already in scan only mode, ignore this command.
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            @Override
            public void exit() {
                // Inform sar manager that scan only is being disabled
                mSarManager.setScanOnlyWifiState(WifiManager.WIFI_STATE_DISABLED);
                mWakeupController.stop();
            }
        }

        private class ConnectModeState extends State {
            @Override
            public void enter() {
                Log.d(TAG, "entering ConnectModeState");
                mQtiClientModeImpl.setOperationalMode(ClientModeImpl.CONNECT_MODE,
                        mClientInterfaceName);
                updateConnectModeState(WifiManager.WIFI_STATE_ENABLED,
                        WifiManager.WIFI_STATE_ENABLING);

                // Inform sar manager that wifi is Enabled
                mSarManager.setClientWifiState(WifiManager.WIFI_STATE_ENABLED);
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_SWITCH_TO_CONNECT_MODE:
                        int newRole = message.arg1;
                        // Already in connect mode, only switching the connectivity roles.
                        if (newRole != mRole) {
                            mRole = newRole;
                        }
                        break;
                    case CMD_SWITCH_TO_SCAN_ONLY_MODE:
                        updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_ENABLED);
                        return NOT_HANDLED; // Handled in StartedState.
                    case CMD_INTERFACE_DOWN:
                        updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_UNKNOWN);
                        return NOT_HANDLED; // Handled in StartedState.
                    case CMD_INTERFACE_STATUS_CHANGED:
                        boolean isUp = message.arg1 == 1;
                        if (isUp == mIfaceIsUp) {
                            break;  // no change
                        }
                        if (!isUp) {
                            if (!mQtiClientModeImpl.isConnectedMacRandomizationEnabled()) {
                                // Handle the error case where our underlying interface went down if
                                // we do not have mac randomization enabled (b/72459123).
                                // if the interface goes down we should exit and go back to idle
                                // state.
                                updateConnectModeState(WifiManager.WIFI_STATE_UNKNOWN,
                                        WifiManager.WIFI_STATE_ENABLED);
                            } else {
                                return HANDLED; // For MAC randomization, ignore...
                            }
                        }
                        return NOT_HANDLED; // Handled in StartedState.
                    case CMD_INTERFACE_DESTROYED:
                        updateConnectModeState(WifiManager.WIFI_STATE_DISABLING,
                                WifiManager.WIFI_STATE_ENABLED);
                        return NOT_HANDLED; // Handled in StartedState.
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            @Override
            public void exit() {
                updateConnectModeState(WifiManager.WIFI_STATE_DISABLED,
                        WifiManager.WIFI_STATE_DISABLING);

                // Inform sar manager that wifi is being disabled
                mSarManager.setClientWifiState(WifiManager.WIFI_STATE_DISABLED);
            }
        }
    }
}
