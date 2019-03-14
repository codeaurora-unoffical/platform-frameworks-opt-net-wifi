/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static android.net.wifi.WifiManager.WIFI_MODE_FULL;
import static android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF;
import static android.net.wifi.WifiManager.WIFI_MODE_NO_LOCKS_HELD;
import static android.net.wifi.WifiManager.WIFI_MODE_SCAN_ONLY;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import android.net.wifi.WifiConfiguration;

/**
 * WifiController is the class used to manage on/off state of WifiStateMachine for various operating
 * modes (normal, airplane, wifi hotspot, etc.).
 */
public class WifiController extends StateMachine {
    private static final String TAG = "WifiController";
    private static boolean DBG = false;
    private Context mContext;
    private boolean mScreenOff;
    private boolean mDeviceIdle;
    private int mPluggedType;
    private int mStayAwakeConditions;
    private long mIdleMillis;
    private int mSleepPolicy;
    private boolean mFirstUserSignOnSeen = false;
    private boolean mStaAndApConcurrency = false;
    private boolean mRestartStaSapStack = false;
    private AlarmManager mAlarmManager;
    private PendingIntent mIdleIntent;
    private static final int IDLE_REQUEST = 0;

    /**
     * See {@link Settings.Global#WIFI_IDLE_MS}. This is the default value if a
     * Settings.Global value is not present. This timeout value is chosen as
     * the approximate point at which the battery drain caused by Wi-Fi
     * being enabled but not active exceeds the battery drain caused by
     * re-establishing a connection to the mobile data network.
     */
    private static final long DEFAULT_IDLE_MS = 15 * 60 * 1000; /* 15 minutes */

    /**
     * See {@link Settings.Global#WIFI_REENABLE_DELAY_MS}.  This is the default value if a
     * Settings.Global value is not present.  This is the minimum time after wifi is disabled
     * we'll act on an enable.  Enable requests received before this delay will be deferred.
     */
    private static final long DEFAULT_REENABLE_DELAY_MS = 500;

    // finding that delayed messages can sometimes be delivered earlier than expected
    // probably rounding errors..  add a margin to prevent problems
    private static final long DEFER_MARGIN_MS = 5;

    NetworkInfo mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI, 0, "WIFI", "");

    private static final String ACTION_DEVICE_IDLE =
            "com.android.server.WifiManager.action.DEVICE_IDLE";

    /* References to values tracked in WifiService */
    private final WifiStateMachine mWifiStateMachine;
    private SoftApStateMachine mSoftApStateMachine = null;
    private final WifiSettingsStore mSettingsStore;
    private final WifiLockManager mWifiLockManager;
    private final WifiApConfigStore mWifiApConfigStore;

    /**
     * Temporary for computing UIDS that are responsible for starting WIFI.
     * Protected by mWifiStateTracker lock.
     */
    private final WorkSource mTmpWorkSource = new WorkSource();

    private long mReEnableDelayMillis;

    private FrameworkFacade mFacade;

    private static final int BASE = Protocol.BASE_WIFI_CONTROLLER;

    static final int CMD_EMERGENCY_MODE_CHANGED        = BASE + 1;
    static final int CMD_SCREEN_ON                     = BASE + 2;
    static final int CMD_SCREEN_OFF                    = BASE + 3;
    static final int CMD_BATTERY_CHANGED               = BASE + 4;
    static final int CMD_DEVICE_IDLE                   = BASE + 5;
    static final int CMD_LOCKS_CHANGED                 = BASE + 6;
    static final int CMD_SCAN_ALWAYS_MODE_CHANGED      = BASE + 7;
    static final int CMD_WIFI_TOGGLED                  = BASE + 8;
    static final int CMD_AIRPLANE_TOGGLED              = BASE + 9;
    static final int CMD_SET_AP                        = BASE + 10;
    static final int CMD_DEFERRED_TOGGLE               = BASE + 11;
    static final int CMD_USER_PRESENT                  = BASE + 12;
    static final int CMD_AP_START_FAILURE              = BASE + 13;
    static final int CMD_EMERGENCY_CALL_STATE_CHANGED  = BASE + 14;
    static final int CMD_AP_STOPPED                    = BASE + 15;
    static final int CMD_STA_START_FAILURE             = BASE + 16;
    // Command used to trigger a wifi stack restart when in active mode
    static final int CMD_RESTART_WIFI                  = BASE + 17;
    // Internal command used to complete wifi stack restart
    private static final int CMD_RESTART_WIFI_CONTINUE = BASE + 18;
    private static final int CMD_WIFI_ENABLED          = BASE + 19;
    private static final int CMD_WIFI_DISABLED         = BASE + 20;
    private static final int CMD_AP_STARTED            = BASE + 21;
    private static final int CMD_AP_STOP_FAILURE       = BASE + 22;
    private static final int CMD_WIFI_STOP_FAILURE      = BASE + 23;

    private DefaultState mDefaultState = new DefaultState();
    private StaEnabledState mStaEnabledState = new StaEnabledState();
    private ApStaDisabledState mApStaDisabledState = new ApStaDisabledState();
    private StaDisabledWithScanState mStaDisabledWithScanState = new StaDisabledWithScanState();
    private ApEnabledState mApEnabledState = new ApEnabledState();
    private QcApStaEnabledState mQcApStaEnabledState = new QcApStaEnabledState();
    private DeviceActiveState mDeviceActiveState = new DeviceActiveState();
    private DeviceInactiveState mDeviceInactiveState = new DeviceInactiveState();
    private ScanOnlyLockHeldState mScanOnlyLockHeldState = new ScanOnlyLockHeldState();
    private FullLockHeldState mFullLockHeldState = new FullLockHeldState();
    private FullHighPerfLockHeldState mFullHighPerfLockHeldState = new FullHighPerfLockHeldState();
    private NoLockHeldState mNoLockHeldState = new NoLockHeldState();
    private EcmState mEcmState = new EcmState();
    private QcStaEnablingState mQcStaEnablingState = new QcStaEnablingState();
    private QcStaDisablingState mQcStaDisablingState = new QcStaDisablingState();
    private QcApEnablingState mQcApEnablingState = new QcApEnablingState();
    private QcApDisablingState mQcApDisablingState = new QcApDisablingState();
    private QcApStaEnablingState mQcApStaEnablingState = new QcApStaEnablingState();
    private QcApStaDisablingState mQcApStaDisablingState = new QcApStaDisablingState();

    WifiController(Context context, WifiStateMachine wsm, WifiSettingsStore wss,
            WifiLockManager wifiLockManager, Looper looper, FrameworkFacade f,
            WifiApConfigStore wacs) {
        super(TAG, looper);
        mFacade = f;
        mContext = context;
        mWifiStateMachine = wsm;
        mSettingsStore = wss;
        mWifiLockManager = wifiLockManager;
        mWifiApConfigStore = wacs;

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        Intent idleIntent = new Intent(ACTION_DEVICE_IDLE, null);
        mIdleIntent = mFacade.getBroadcast(mContext, IDLE_REQUEST, idleIntent, 0);

        addState(mDefaultState);
            addState(mApStaDisabledState, mDefaultState);
            addState(mStaEnabledState, mDefaultState);
            addState(mQcStaEnablingState, mDefaultState);
            addState(mQcStaDisablingState, mDefaultState);
            addState(mQcApEnablingState, mDefaultState);
            addState(mQcApDisablingState, mDefaultState);
            addState(mQcApStaEnablingState, mDefaultState);
            addState(mQcApStaDisablingState, mDefaultState);
            addState(mQcApStaEnabledState, mDefaultState);
                addState(mDeviceActiveState, mStaEnabledState);
                addState(mDeviceInactiveState, mStaEnabledState);
                    addState(mScanOnlyLockHeldState, mDeviceInactiveState);
                    addState(mFullLockHeldState, mDeviceInactiveState);
                    addState(mFullHighPerfLockHeldState, mDeviceInactiveState);
                    addState(mNoLockHeldState, mDeviceInactiveState);
            addState(mStaDisabledWithScanState, mDefaultState);
            addState(mApEnabledState, mDefaultState);
            addState(mEcmState, mDefaultState);

        boolean isAirplaneModeOn = mSettingsStore.isAirplaneModeOn();
        boolean isWifiEnabled = mSettingsStore.isWifiToggleEnabled();
        boolean isScanningAlwaysAvailable = mSettingsStore.isScanAlwaysAvailable();

        log("isAirplaneModeOn = " + isAirplaneModeOn +
                ", isWifiEnabled = " + isWifiEnabled +
                ", isScanningAvailable = " + isScanningAlwaysAvailable);

        if (isScanningAlwaysAvailable) {
            setInitialState(mStaDisabledWithScanState);
        } else {
            setInitialState(mApStaDisabledState);
        }

        setLogRecSize(100);
        setLogOnlyTransitions(false);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_DEVICE_IDLE);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (action.equals(ACTION_DEVICE_IDLE)) {
                            sendMessage(CMD_DEVICE_IDLE);
                        } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                            mNetworkInfo = (NetworkInfo) intent.getParcelableExtra(
                                    WifiManager.EXTRA_NETWORK_INFO);
                        } else if (action.equals(WifiManager.WIFI_AP_STATE_CHANGED_ACTION)) {
                            int state = intent.getIntExtra(
                                    WifiManager.EXTRA_WIFI_AP_STATE,
                                    WifiManager.WIFI_AP_STATE_FAILED);
                            if (state == WifiManager.WIFI_AP_STATE_FAILED) {
                                loge(TAG + "SoftAP start failed");
                                sendMessage(CMD_AP_START_FAILURE);
                            } else if (state == WifiManager.WIFI_AP_STATE_DISABLED) {
                                sendMessage(CMD_AP_STOPPED);
                            } else if (state == WifiManager.WIFI_AP_STATE_ENABLED) {
                                sendMessage(CMD_AP_STARTED);
                            }
                        } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                            int state = intent.getIntExtra(
                                    WifiManager.EXTRA_WIFI_STATE,
                                    WifiManager.WIFI_STATE_UNKNOWN);
                            if (state == WifiManager.WIFI_STATE_UNKNOWN) {
                                loge(TAG + "Wifi turn on failed");
                                sendMessage(CMD_STA_START_FAILURE);
                            } else if (state == WifiManager.WIFI_STATE_ENABLED) {
                                sendMessage(CMD_WIFI_ENABLED);
                            } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                                sendMessage(CMD_WIFI_DISABLED);
                            }
                        }
                    }
                },
                new IntentFilter(filter));

        initializeAndRegisterForSettingsChange(looper);
    }

    private void initializeAndRegisterForSettingsChange(Looper looper) {
        Handler handler = new Handler(looper);
        readStayAwakeConditions();
        registerForStayAwakeModeChange(handler);
        readWifiIdleTime();
        registerForWifiIdleTimeChange(handler);
        readWifiSleepPolicy();
        registerForWifiSleepPolicyChange(handler);
        readWifiReEnableDelay();
    }

    private void readStayAwakeConditions() {
        mStayAwakeConditions = mFacade.getIntegerSetting(mContext,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0);
    }

    private void readWifiIdleTime() {
        mIdleMillis = mFacade.getLongSetting(mContext,
                Settings.Global.WIFI_IDLE_MS, DEFAULT_IDLE_MS);
    }

    private void readWifiSleepPolicy() {
        // This should always set to default value because the settings menu to toggle this
        // has been removed now.
        mSleepPolicy = Settings.Global.WIFI_SLEEP_POLICY_NEVER;
    }

    private void readWifiReEnableDelay() {
        mReEnableDelayMillis = mFacade.getLongSetting(mContext,
                Settings.Global.WIFI_REENABLE_DELAY_MS, DEFAULT_REENABLE_DELAY_MS);
    }

    /**
     * Observes settings changes to scan always mode.
     */
    private void registerForStayAwakeModeChange(Handler handler) {
        ContentObserver contentObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                readStayAwakeConditions();
            }
        };

        mFacade.registerContentObserver(mContext,
                Settings.Global.getUriFor(Settings.Global.STAY_ON_WHILE_PLUGGED_IN), false,
                contentObserver);
    }

    /**
     * Observes settings changes to wifi idle time.
     */
    private void registerForWifiIdleTimeChange(Handler handler) {
        ContentObserver contentObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                readWifiIdleTime();
            }
        };

        mFacade.registerContentObserver(mContext,
                Settings.Global.getUriFor(Settings.Global.WIFI_IDLE_MS), false, contentObserver);
    }

    /**
     * Observes changes to wifi sleep policy
     */
    private void registerForWifiSleepPolicyChange(Handler handler) {
        ContentObserver contentObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                readWifiSleepPolicy();
            }
        };
        mFacade.registerContentObserver(mContext,
                Settings.Global.getUriFor(Settings.Global.WIFI_SLEEP_POLICY), false,
                contentObserver);
    }

    /**
     * Determines whether the Wi-Fi chipset should stay awake or be put to
     * sleep. Looks at the setting for the sleep policy and the current
     * conditions.
     *
     * @see #shouldDeviceStayAwake(int)
     */
    private boolean shouldWifiStayAwake(int pluggedType) {
        if (mSleepPolicy == Settings.Global.WIFI_SLEEP_POLICY_NEVER) {
            // Never sleep
            return true;
        } else if ((mSleepPolicy == Settings.Global.WIFI_SLEEP_POLICY_NEVER_WHILE_PLUGGED) &&
                (pluggedType != 0)) {
            // Never sleep while plugged, and we're plugged
            return true;
        } else {
            // Default
            return shouldDeviceStayAwake(pluggedType);
        }
    }

    /**
     * Determine whether the bit value corresponding to {@code pluggedType} is set in
     * the bit string mStayAwakeConditions. This determines whether the device should
     * stay awake based on the current plugged type.
     *
     * @param pluggedType the type of plug (USB, AC, or none) for which the check is
     * being made
     * @return {@code true} if {@code pluggedType} indicates that the device is
     * supposed to stay awake, {@code false} otherwise.
     */
    private boolean shouldDeviceStayAwake(int pluggedType) {
        return (mStayAwakeConditions & pluggedType) != 0;
    }

    private void updateBatteryWorkSource() {
        mTmpWorkSource.clear();
        if (mDeviceIdle) {
            mTmpWorkSource.add(mWifiLockManager.createMergedWorkSource());
        }
        mWifiStateMachine.updateBatteryWorkSource(mTmpWorkSource);
    }

    private void logStateAndMessage(Message message, State state) {
        if (DBG ) {
            logd(" " + state.getClass().getSimpleName() + ", message: " + message);
        }
    }

    public void setSoftApStateMachine(SoftApStateMachine machine, boolean enable) {
        mSoftApStateMachine = machine;
        mStaAndApConcurrency = enable;
        mWifiStateMachine.setStaSoftApConcurrency(enable);
        Slog.d(TAG, "set mStaAndApConcurrency="+mStaAndApConcurrency);
    }

    void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            DBG = true;
        } else {
            DBG = false;
        }
    }

    /*
     *    WifiController states
     *
     * ApStaDisabledState : Indicates that both Wifi Station and SoftAP are in turn
     *                      OFF state.
     * StaEnabledState    : Indicates that only Wifi station in ON state.
     * ApEnabledState     : Indicates that only SoftAP in ON state.
     * QcApStaEnabledState  : Indicates that both Wifi station nad SoftAP are in turn
     *                      ON state.
     *
     *   ----------------------------------------------
     *     New states added in WifiController
     *   ----------------------------------------------
     *
     *
     * QcApEnablingState   : Indicates that SAP turn ON in progress with Wi-Fi STA being OFF.
     *
     * QcApDisablingState  : Indicates that SAP turn OFF in progress with Wi-Fi STA being OFF.
     *
     * QcStaEnablingState  : Indicates that STA turn ON in progress with Wi-Fi SAP being OFF.
     *
     * QcStaDisablingState : Indicates that STA turn OFF in progress with Wi-Fi SAP being OFF.
     *
     * QcApStaEnablingState : This state is entered when either of STA/SAP is getting
     *                      TURNED ON with the other already ON. This is an interim state
     *                      which aims at serializing the OFF request for STA/SAP while the
     *                      ON is in progress.
     *
     * QcApStaDisablingState : This state is entered when either of STA/ SAP is getting
     *                       TURNED OFF with the other already ON. This is an interim state
     *                       which aims at serializing the ON request for STA/SAP while the
     *                       OFF is in progress.
     *
     *
     *                 ------------------
     *                  ApStaDisabledState
     *                   ------------------
     *                     /      \
     *   -> Turn ON STA   /        \ Turn on SAP <-
     *                   /          \
     *                  /            \
     *         ---------------     --------------
     *         StaEnabledState     ApEnabledState
     *         ---------------     --------------
     *                 \               /
     *                  \             /
     *    -> Turn ON SAP \           /  Turn on STA <-
     *                    \         /
     *                   ---------------
     *                   QcApStaEnablingState
     *                   ---------------
     *                        |     |
     *                        |     |
     * -> CMD_AP_STARTED      |     |    CMD_STA_STARTED <-
     *(SAP turn on completed) |     |    (STA turn on completed)
     *                        |     |
     *                  ---------------------
     *                   QcApStaEnabledState
     *                  ----------------------
     *                         /  \
                              /    \
     * -> Turn on OFF STA    /      \     Turn OFF SAP
     *                      /        \
     *                 ------------------------
     *                  QcApStaDisablingState
     *                 ------------------------
     *                           /\
     *                          /  \
     * -> CMD_WIFI_DISABLED    /    \    CMD_AP_STOPPED <-
     *(STA turn OFF completed)/      \   (SAP turn OFF completed)
     *                       /        \
     *                      /          \
     *      -------------------      -------------
     *         ApEnabledState        StaEnabledState
     *      -------------------      ---------------
     */

    class DefaultState extends State {
        @Override
        public boolean processMessage(Message msg) {
            logStateAndMessage(msg, this);

            switch (msg.what) {
                case CMD_SCREEN_ON:
                    mAlarmManager.cancel(mIdleIntent);
                    mScreenOff = false;
                    mDeviceIdle = false;
                    updateBatteryWorkSource();
                    break;
                case CMD_SCREEN_OFF:
                    mScreenOff = true;
                    /*
                    * Set a timer to put Wi-Fi to sleep, but only if the screen is off
                    * AND the "stay on while plugged in" setting doesn't match the
                    * current power conditions (i.e, not plugged in, plugged in to USB,
                    * or plugged in to AC).
                    */
                    if (!shouldWifiStayAwake(mPluggedType)) {
                        //Delayed shutdown if wifi is connected
                        if (mNetworkInfo.getDetailedState() ==
                                NetworkInfo.DetailedState.CONNECTED) {
                            if (DBG) Slog.d(TAG, "set idle timer: " + mIdleMillis + " ms");
                            mAlarmManager.set(AlarmManager.RTC_WAKEUP,
                                    System.currentTimeMillis() + mIdleMillis, mIdleIntent);
                        } else {
                            sendMessage(CMD_DEVICE_IDLE);
                        }
                    }
                    break;
                case CMD_DEVICE_IDLE:
                    mDeviceIdle = true;
                    updateBatteryWorkSource();
                    break;
                case CMD_BATTERY_CHANGED:
                    /*
                    * Set a timer to put Wi-Fi to sleep, but only if the screen is off
                    * AND we are transitioning from a state in which the device was supposed
                    * to stay awake to a state in which it is not supposed to stay awake.
                    * If "stay awake" state is not changing, we do nothing, to avoid resetting
                    * the already-set timer.
                    */
                    int pluggedType = msg.arg1;
                    if (DBG) Slog.d(TAG, "battery changed pluggedType: " + pluggedType);
                    if (mScreenOff && shouldWifiStayAwake(mPluggedType) &&
                            !shouldWifiStayAwake(pluggedType)) {
                        long triggerTime = System.currentTimeMillis() + mIdleMillis;
                        if (DBG) Slog.d(TAG, "set idle timer for " + mIdleMillis + "ms");
                        mAlarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, mIdleIntent);
                    }

                    mPluggedType = pluggedType;
                    break;
                case CMD_SET_AP:
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                case CMD_LOCKS_CHANGED:
                case CMD_WIFI_TOGGLED:
                case CMD_AIRPLANE_TOGGLED:
                case CMD_EMERGENCY_MODE_CHANGED:
                case CMD_EMERGENCY_CALL_STATE_CHANGED:
                case CMD_AP_START_FAILURE:
                case CMD_AP_STOPPED:
                case CMD_STA_START_FAILURE:
                case CMD_RESTART_WIFI:
                case CMD_RESTART_WIFI_CONTINUE:
                case CMD_WIFI_ENABLED:
                case CMD_WIFI_DISABLED:
                case CMD_AP_STARTED:
                case CMD_WIFI_STOP_FAILURE:
                case CMD_AP_STOP_FAILURE:
                    break;
                case CMD_USER_PRESENT:
                    mFirstUserSignOnSeen = true;
                    break;
                case CMD_DEFERRED_TOGGLE:
                    log("DEFERRED_TOGGLE ignored due to state change");
                    break;
                default:
                    throw new RuntimeException("WifiController.handleMessage " + msg.what);
            }
            return HANDLED;
        }

    }

    class ApStaDisabledState extends State {
        private int mDeferredEnableSerialNumber = 0;
        private boolean mHaveDeferredEnable = false;
        private long mDisabledTimestamp;

        @Override
        public void enter() {
            // Supplicant can't restart right away, so not the time we switched off
            mDisabledTimestamp = SystemClock.elapsedRealtime();
            mDeferredEnableSerialNumber++;
            mHaveDeferredEnable = false;
        }
        @Override
        public boolean processMessage(Message msg) {
            logStateAndMessage(msg, this);
            switch (msg.what) {
                case CMD_WIFI_TOGGLED:
                case CMD_AIRPLANE_TOGGLED:
                    if (mSettingsStore.isWifiToggleEnabled()) {
                        if (doDeferEnable(msg)) {
                            if (mHaveDeferredEnable) {
                                //  have 2 toggles now, inc serial number an ignore both
                                mDeferredEnableSerialNumber++;
                            }
                            mHaveDeferredEnable = !mHaveDeferredEnable;
                            break;
                        }
                        if (mDeviceIdle == false) {
                            if (mStaAndApConcurrency) {
                                transitionTo(mQcStaEnablingState);
                            } else {
                                // wifi is toggled, we need to explicitly tell WifiStateMachine that we
                                // are headed to connect mode before going to the DeviceActiveState
                                // since that will start supplicant and WifiStateMachine may not know
                                // what state to head to (it might go to scan mode).
                                mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE);
                                transitionTo(mDeviceActiveState);
                            }
                        } else {
                            checkLocksAndTransitionWhenDeviceIdle();
                        }
                    } else if (mSettingsStore.isScanAlwaysAvailable()) {
                        transitionTo(mStaDisabledWithScanState);
                    }
                    break;
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    if (mSettingsStore.isScanAlwaysAvailable()) {
                        transitionTo(mStaDisabledWithScanState);
                    }
                    break;
                case CMD_SET_AP:
                    if (msg.arg1 == 1) {
                        if (msg.arg2 == 0) { // previous wifi state has not been saved yet
                            mSettingsStore.setWifiSavedState(WifiSettingsStore.WIFI_DISABLED);
                        }

                        /* For STA+SAP concurrency request to start SoftAP is handled in QcApEnablingState. */
                        if (mStaAndApConcurrency) {
                            transitionTo(mQcApEnablingState);
                        } else {
                            mWifiStateMachine.setHostApRunning((SoftApModeConfiguration) msg.obj,
                                    true);
                            transitionTo(mApEnabledState);
                        }
                    }
                    break;
                case CMD_DEFERRED_TOGGLE:
                    if (msg.arg1 != mDeferredEnableSerialNumber) {
                        log("DEFERRED_TOGGLE ignored due to serial mismatch");
                        break;
                    }
                    log("DEFERRED_TOGGLE handled");
                    sendMessage((Message)(msg.obj));
                    break;
                case CMD_RESTART_WIFI_CONTINUE:
                    if (mStaAndApConcurrency) {
                        log("ApStaDisabledState: CMD_RESTART_WIFI_CONTINUE -> mQcStaEnablingState");
                        if (mRestartStaSapStack) {
                            deferMessage(msg);
                        }
                        transitionTo(mQcStaEnablingState);
                    } else {
                        transitionTo(mDeviceActiveState);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        private boolean doDeferEnable(Message msg) {
            long delaySoFar = SystemClock.elapsedRealtime() - mDisabledTimestamp;
            if (delaySoFar >= mReEnableDelayMillis) {
                return false;
            }

            log("WifiController msg " + msg + " deferred for " +
                    (mReEnableDelayMillis - delaySoFar) + "ms");

            // need to defer this action.
            Message deferredMsg = obtainMessage(CMD_DEFERRED_TOGGLE);
            deferredMsg.obj = Message.obtain(msg);
            deferredMsg.arg1 = ++mDeferredEnableSerialNumber;
            sendMessageDelayed(deferredMsg, mReEnableDelayMillis - delaySoFar + DEFER_MARGIN_MS);
            return true;
        }

    }

    class QcStaEnablingState extends State {

        @Override
        public void enter() {
            if (DBG) {
                Slog.d(TAG, "QcStaEnablingState enter");
            }
            mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE);
            mWifiStateMachine.setSupplicantRunning(true);
        }

        @Override
        public boolean processMessage(Message msg) {
            logStateAndMessage(msg, this);
            switch(msg.what) {
                case CMD_STA_START_FAILURE:
                    log("QcStaEnablingState: CMD_STA_START_FAILURE->mApStaDisabledState");
                    transitionTo(mApStaDisabledState);
                    break;
                case CMD_WIFI_ENABLED:
                    log("QcStaEnablingState: CMD_WIFI_ENABLED->mDeviceActiveState");
                    transitionTo(mDeviceActiveState);
                    break;
                case CMD_WIFI_TOGGLED:
                    log("QcStaEnablingState: CMD_WIFI_TOGGLED defered");
                    deferMessage(msg);
                    break;
                case CMD_SET_AP:
                    log("QcStaEnablingState: CMD_SET_AP defered");
                    deferMessage(obtainMessage(msg.what, msg.arg1, 1, msg.obj));
                    break;
                case CMD_AIRPLANE_TOGGLED:
                    log("QcStaEnablingState: CMD_AIRPLANE_TOGGLED defered");
                    deferMessage(msg);
                    break;
                case CMD_RESTART_WIFI_CONTINUE:
                    log("QcStaEnablingState: CMD_RESTART_WIFI_CONTINUE defered");
                    deferMessage(msg);
                    break;
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    log("QcStaEnablingState: CMD_SCAN_ALWAYS_MODE_CHANGED defered");
                    deferMessage(msg);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            if (DBG) {
                Slog.d(TAG, "QcStaEnablingState exit");
            }
        }
    }

    class QcStaDisablingState extends State {

        @Override
        public void enter() {
            if (DBG) {
                Slog.d(TAG, "QcStaDisablingState enter");
            }
            mWifiStateMachine.setSupplicantRunning(false);
            mWifiStateMachine.clearANQPCache();
        }

        @Override
        public boolean processMessage(Message msg) {
            logStateAndMessage(msg, this);

            switch(msg.what) {
                case CMD_WIFI_STOP_FAILURE:
                case CMD_WIFI_DISABLED:
                    log("QcStaDisablingState: CMD_WIFI_DISABLED->mApStaDisabledState");
                    transitionTo(mApStaDisabledState);
                    break;
                case CMD_WIFI_TOGGLED:
                    log("QcStaDisablingState: CMD_WIFI_TOGGLED defered");
                    deferMessage(msg);
                    break;
                case CMD_SET_AP:
                    log("QcStaDisablingState: CMD_SET_AP defered");
                    deferMessage(msg);
                    break;
                case CMD_AIRPLANE_TOGGLED:
                    log("QcStaDisablingState: CMD_AIRPLANE_TOGGLED defered");
                    deferMessage(msg);
                    break;
                case CMD_RESTART_WIFI_CONTINUE:
                    log("QcStaDisablingState: CMD_RESTART_WIFI_CONTINUE defered");
                    deferMessage(msg);
                    break;
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    log("QcStaDisablingState: CMD_SCAN_ALWAYS_MODE_CHANGED defered");
                    deferMessage(msg);
                    break;
                default:
                    return NOT_HANDLED;
             }
             return HANDLED;
        }

        @Override
        public void exit() {
            if (DBG) {
                Slog.d(TAG, "QcStaDisablingState exit");
            }
        }
    }

    class QcApEnablingState extends State {

        @Override
        public void enter() {
            if (DBG) {
                Slog.d(TAG, "QcApEnablingState enter");
            }

            mSoftApStateMachine.setHostApRunning((SoftApModeConfiguration) getCurrentMessage().obj,
                true);
        }

        @Override
        public boolean processMessage(Message msg) {
            logStateAndMessage(msg, this);
            switch(msg.what) {
                case CMD_AP_START_FAILURE:
                    log("QcApEnablingState: CMD_AP_START_FAILURE->mApStaDisabledState");
                    transitionTo(mApStaDisabledState);
                    break;
                case CMD_AP_STARTED:
                    log("QcApEnablingState: CMD_AP_STARTED->mApEnabledState");
                    transitionTo(mApEnabledState);
                    break;
                case CMD_WIFI_TOGGLED:
                    log("QcApEnablingState: CMD_WIFI_TOGGLED defered");
                    deferMessage(msg);
                    break;
                case CMD_SET_AP:
                    log("QcApEnablingState: CMD_SET_AP defered");
                    deferMessage(obtainMessage(msg.what, msg.arg1, 1, msg.obj));
                    break;
                case CMD_AIRPLANE_TOGGLED:
                    log("QcApEnablingState: CMD_AIRPLANE_TOGGLED defered");
                    deferMessage(obtainMessage(msg.what, msg.arg1, 1, msg.obj));
                    break;
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    log("QcApEnablingState: CMD_SCAN_ALWAYS_MODE_CHANGED defered");
                    deferMessage(msg);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            if (DBG) {
                Slog.d(TAG, "QcApEnablingState exit");
            }
        }
    }

    class QcApDisablingState extends State {

        @Override
        public void enter() {
            mSoftApStateMachine.setHostApRunning((SoftApModeConfiguration) getCurrentMessage().obj,
               false);
        }

        @Override
        public boolean processMessage(Message msg) {
            logStateAndMessage(msg, this);
            switch(msg.what) {
                case CMD_AP_STOPPED:
                    if (mSettingsStore.isScanAlwaysAvailable()) {
                        log("QcApDisablingState: CMD_AP_STOPPED->mStaDisabledWithScanState");
                        transitionTo(mStaDisabledWithScanState);
                    } else if (mSettingsStore.isAirplaneModeOn()
                            && (mWifiStateMachine.getOperationalMode()
                                  != WifiStateMachine.CONNECT_MODE)) {
                        // isScanAlwaysAvailable can be false if airplane on.
                        // If Wi-Fi in scan mode, switch to connect mode & disable it.
                        log("ApDisablingState: CMD_AP_STOPPED->mQcStaDisablingState");
                        mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE);
                        transitionTo(mQcStaDisablingState);
                    } else {
                        log("QcApDisablingState: CMD_AP_STOPPED->mApStaDisabledState");
                        transitionTo(mApStaDisabledState);
                    }
                    break;
                case CMD_AP_STOP_FAILURE:
                    if (mSettingsStore.isScanAlwaysAvailable()) {
                        log("QcApDisablingState: CMD_AP_STOP_FAILURE->mStaDisabledWithScanState");
                        transitionTo(mStaDisabledWithScanState);
                    } else {
                        log("QcApDisablingState: CMD_AP_STOP_FAILURE->mApStaDisabledState");
                        transitionTo(mApStaDisabledState);
                    }
                    break;
                case CMD_SET_AP:
                    log("QcApDisablingState: CMD_SET_AP defered");
                    deferMessage(msg);
                    break;
                case CMD_WIFI_TOGGLED:
                    log("QcApDisablingState: CMD_WIFI_TOGGLED defered");
                    deferMessage(msg);
                    break;
                case CMD_AIRPLANE_TOGGLED:
                    log("QcApDisablingState: CMD_AIRPLANE_TOGGLED defered");
                    deferMessage(msg);
                    break;
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    log("QcApDisablingState: CMD_SCAN_ALWAYS_MODE_CHANGED defered");
                    deferMessage(msg);
                    break;
                default:
                    return NOT_HANDLED;
             }
             return HANDLED;
        }

        @Override
        public void exit() {
             Slog.d(TAG, "QcApDisablingState: exit()");
        }
    }

    class QcApStaEnablingState extends State {

        @Override
        public void enter() {
            if (DBG) {
                Slog.d(TAG,"QcApStaEnablingState enter");
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            logStateAndMessage(msg, this);
            switch(msg.what) {
                case CMD_SET_AP:
                    log("QcApStaEnablingState defer CMD_SET_AP");
                    deferMessage(obtainMessage(msg.what, msg.arg1, 1, msg.obj));
                    break;
                case CMD_WIFI_TOGGLED:
                    log("QcApStaEnablingState defer CMD_WIFI_TOGGLED");
                    deferMessage(msg);
                    break;
                case CMD_AIRPLANE_TOGGLED:
                    log("QcApStaEnablingState defer CMD_AIRPLANE_TOGGLED");
                    deferMessage(msg);
                    break;
                case CMD_WIFI_ENABLED:
                    log("QcApStaEnablingState: CMD_WIFI_ENABLED-> mQcApStaEnabledState");
                    transitionTo(mQcApStaEnabledState);
                    break;
                case CMD_AP_STARTED:
                    log("QcApStaEnablingState: CMD_AP_STARTED-> mQcApStaEnabledState");
                    transitionTo(mQcApStaEnabledState);
                    break;
                case CMD_STA_START_FAILURE:
                    log("QcApStaEnablingState: CMD_STA_START_FAILURE-> mApEnabledState");
                    transitionTo(mApEnabledState);
                    break;
                case CMD_AP_START_FAILURE:
                    log("QcApStaEnablingState: CMD_AP_START_FAILURE-> mStaEnabledState");
                    transitionTo(mStaEnabledState);
                    break;
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    log("QcApStaEnablingState: CMD_SCAN_ALWAYS_MODE_CHANGED defered");
                    deferMessage(msg);
                    break;
                default :
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            if (DBG) {
               Slog.d(TAG, "QcApStaEnablingState exit");
            }
        }
    }

    class QcApStaDisablingState extends State {

        @Override
        public void enter() {
            if (DBG) {
               Slog.d(TAG,"QcApStaDisablingState enter");
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            logStateAndMessage(msg, this);
            switch(msg.what) {
                case CMD_SET_AP:
                    log("QcApStaDisablingState defer CMD_SET_AP");
                    deferMessage(obtainMessage(msg.what, msg.arg1,  1, msg.obj));
                    break;
                case CMD_WIFI_TOGGLED:
                    log("QcApStaDisablingState defer CMD_WIFI_TOGGLED");
                    deferMessage(msg);
                    break;
                case CMD_AIRPLANE_TOGGLED:
                    log("QcApStaDisablingState defer CMD_AIRPLANE_TOGGLED");
                    deferMessage(msg);
                    break;
                case CMD_WIFI_ENABLED:
                    log("QcApStaDisablingState CMD_WIFI_ENABLED ignored");
                    break;
                case CMD_WIFI_DISABLED:
                    log("QcApStaDisablingState: CMD_WIFI_DISABLED-> mApEnabledState");
                    transitionTo(mApEnabledState);
                    break;
                case CMD_AP_STARTED:
                    log("QcApStaDisablingState CMD_AP_STARTED ignored");
                    break;
                case CMD_AP_STOPPED:
                    log("QcApStaDisablingState: CMD_AP_STOPPED->StaEnabledState");
                    transitionTo(mStaEnabledState);
                    break;
                case CMD_STA_START_FAILURE:
                    log("QcApStaDisablingState: CMD_STA_START_FAILURE dropped");
                    break;
                case CMD_RESTART_WIFI:
                    log("QcApStaDisablingState defer CMD_RESTART_WIFI");
                    deferMessage(msg);
                    break;
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    log("QcApStaDisablingState: CMD_SCAN_ALWAYS_MODE_CHANGED defered");
                    deferMessage(msg);
                    break;
                default :
                    return NOT_HANDLED;
           }
           return HANDLED;
        }

        @Override
        public void exit() {
            if (DBG) {
                Slog.d(TAG, "QcApStaDisablingState exit");
            }
        }
    }

    class QcApStaEnabledState extends State {
        private State mPendingState = null;
        private int mDeferredEnableSerialNumber = 0;
        private boolean mHaveDeferredEnable = false;
        private long mDisabledTimestamp;

        @Override
        public void enter() {
           if (DBG) {
               Slog.d(TAG,"QcApStaEnabledState enter");
           }
        }

        @Override
        public boolean processMessage(Message msg) {
           switch (msg.what) {
            case CMD_SET_AP:
                if (msg.arg1 == 0) {
                    if (DBG) {
                        Slog.d(TAG,"QcApStaEnabledState:CMD_SET_AP:setHostApRunning(false)-> mQcApStaDisablingState");
                    }
                    mSoftApStateMachine.setHostApRunning(null, false);
                    transitionTo(mQcApStaDisablingState);
                }
                break;
            case CMD_WIFI_TOGGLED:
                if (!mSettingsStore.isWifiToggleEnabled()) {
                    if (doDeferEnable(msg)) {
                        if (mHaveDeferredEnable) {
                            //  have 2 toggles now, inc serial number an ignore both
                            mDeferredEnableSerialNumber++;
                        }
                        mHaveDeferredEnable = !mHaveDeferredEnable;
                        break;
                    }
                    if (mSettingsStore.isScanAlwaysAvailable()) {
                        log("QcApStaEnabledState:CMD_WIFI_TOGGLED:set:SCAN_ONLY_WITH_WIFI_OFF_MODE");
                        mWifiStateMachine.setOperationalMode(WifiStateMachine.SCAN_ONLY_WITH_WIFI_OFF_MODE);
                    } else {
                        log("QcApStaEnabledState:CMD_WIFI_TOGGLED:setSupplicantRunning(false)");
                        mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE);
                        mWifiStateMachine.setSupplicantRunning(false);
                    }
                    transitionTo(mQcApStaDisablingState);
                }
                break;
            case CMD_AIRPLANE_TOGGLED:
                log("QcApStaEnabledState: CMD_AIRPLANE_TOGGLED-> mQcApStaDisablingState");
                mSoftApStateMachine.setHostApRunning(null, false);
                deferMessage(msg);
                transitionTo(mQcApStaDisablingState);
                break;
            case CMD_STA_START_FAILURE:
                transitionTo(mApEnabledState);
                break;
            case CMD_AP_START_FAILURE:
                transitionTo(mStaEnabledState);
                break;
            case CMD_RESTART_WIFI:
                log("QcApStaEnabledState: CMD_RESTART_WIFI -> setHostApRunning(false) -> mQcApStaDisablingState");
                mSoftApStateMachine.setHostApRunning(null, false);
                mRestartStaSapStack = true;
                deferMessage(msg);
                transitionTo(mQcApStaDisablingState);
                break;
            default :
                return NOT_HANDLED;
           }
           return HANDLED;
      }

      @Override
       public void exit() {
          if (DBG) {
               Slog.d(TAG,"QcApStaEnabledState exit");
          }
      }

       private boolean doDeferEnable(Message msg) {
            long delaySoFar = SystemClock.elapsedRealtime() - mDisabledTimestamp;
            if (delaySoFar >= mReEnableDelayMillis) {
                return false;
            }

            log("WifiController msg " + msg + " deferred for " +
                    (mReEnableDelayMillis - delaySoFar) + "ms");

            // need to defer this action.
            Message deferredMsg = obtainMessage(CMD_DEFERRED_TOGGLE);
            deferredMsg.obj = Message.obtain(msg);
            deferredMsg.arg1 = ++mDeferredEnableSerialNumber;
            sendMessageDelayed(deferredMsg, mReEnableDelayMillis - delaySoFar + DEFER_MARGIN_MS);
            return true;
        }

    }

    class StaEnabledState extends State {
        @Override
        public void enter() {
            /*
             * For STA+SAP concurrency request to start supplicant is
             * handled in QcStaEnablingState. Hence skip for standalone case
             */
            if (!mStaAndApConcurrency) {
                mWifiStateMachine.setSupplicantRunning(true);
            }
        }
        @Override
        public boolean processMessage(Message msg) {
            logStateAndMessage(msg, this);
            switch (msg.what) {
                case CMD_WIFI_TOGGLED:
                    if (! mSettingsStore.isWifiToggleEnabled()) {
                        if (mSettingsStore.isScanAlwaysAvailable()) {
                            transitionTo(mStaDisabledWithScanState);
                        } else {
                            transitionTo(mQcStaDisablingState);
                        }
                    }
                    break;
                case CMD_AIRPLANE_TOGGLED:
                    /* When wi-fi is turned off due to airplane,
                    * disable entirely (including scan)
                    */
                    if (!mSettingsStore.isWifiToggleEnabled()) {
                        transitionTo(mQcStaDisablingState);
                    }
                    break;
                case CMD_STA_START_FAILURE:
                    if (!mSettingsStore.isScanAlwaysAvailable()) {
                        transitionTo(mApStaDisabledState);
                    } else {
                        transitionTo(mStaDisabledWithScanState);
                    }
                    break;
                case CMD_EMERGENCY_CALL_STATE_CHANGED:
                case CMD_EMERGENCY_MODE_CHANGED:
                    boolean getConfigWiFiDisableInECBM = mFacade.getConfigWiFiDisableInECBM(mContext);
                    log("WifiController msg " + msg + " getConfigWiFiDisableInECBM "
                            + getConfigWiFiDisableInECBM);
                    if ((msg.arg1 == 1) && getConfigWiFiDisableInECBM) {
                        transitionTo(mEcmState);
                    }
                    break;
                case CMD_SET_AP:
                    if (msg.arg1 == 1) {
                        if (mStaAndApConcurrency) {
                            Slog.d(TAG,"StaEnabledState:CMD_SET_AP:setHostApRunning(true)-> mApStaEnableState");
                            mSoftApStateMachine.setHostApRunning(((SoftApModeConfiguration) msg.obj), true);
                            transitionTo(mQcApStaEnablingState);
                        } else {
                            // remeber that we were enabled
                            mSettingsStore.setWifiSavedState(WifiSettingsStore.WIFI_ENABLED);
                            deferMessage(obtainMessage(msg.what, msg.arg1, 1, msg.obj));
                            transitionTo(mQcStaDisablingState);
                        }
                    }
                    break;
                case CMD_RESTART_WIFI:
                    log("StaEnabledState:CMD_RESTART_WIFI ->QcStaDisablingState");
                    deferMessage(obtainMessage(CMD_RESTART_WIFI_CONTINUE));
                    transitionTo(mQcStaDisablingState);
                    break;
                case CMD_RESTART_WIFI_CONTINUE:
                    if (mStaAndApConcurrency && mRestartStaSapStack) {
                        if (msg.obj != null) {
                            log("StaEnabledState:CMD_RESTART_WIFI_CONTINUE ->mQcApStaEnablingState");
                            mSoftApStateMachine.setHostApRunning(((SoftApModeConfiguration) msg.obj), true);
                            transitionTo(mQcApStaEnablingState);
                        } else {
                            log("StaEnabledState:CMD_RESTART_WIFI_CONTINUE: SoftApConfig obj null. Do nothing");
                        }
                        mRestartStaSapStack = false;
                    }
                    break;
                default:
                    return NOT_HANDLED;

            }
            return HANDLED;
        }
    }

    class StaDisabledWithScanState extends State {
        private int mDeferredEnableSerialNumber = 0;
        private boolean mHaveDeferredEnable = false;
        private long mDisabledTimestamp;

        @Override
        public void enter() {
            // need to set the mode before starting supplicant because WSM will assume we are going
            // in to client mode
            mWifiStateMachine.setOperationalMode(WifiStateMachine.SCAN_ONLY_WITH_WIFI_OFF_MODE);
            mWifiStateMachine.setSupplicantRunning(true);
            // Supplicant can't restart right away, so not the time we switched off
            mDisabledTimestamp = SystemClock.elapsedRealtime();
            mDeferredEnableSerialNumber++;
            mHaveDeferredEnable = false;
            mWifiStateMachine.clearANQPCache();
        }

        @Override
        public boolean processMessage(Message msg) {
            logStateAndMessage(msg, this);
            switch (msg.what) {
                case CMD_WIFI_TOGGLED:
                    if (mSettingsStore.isWifiToggleEnabled()) {
                        if (doDeferEnable(msg)) {
                            if (mHaveDeferredEnable) {
                                // have 2 toggles now, inc serial number and ignore both
                                mDeferredEnableSerialNumber++;
                            }
                            mHaveDeferredEnable = !mHaveDeferredEnable;
                            break;
                        }
                        if (mDeviceIdle == false) {
                            transitionTo(mDeviceActiveState);
                        } else {
                            checkLocksAndTransitionWhenDeviceIdle();
                        }
                    }
                    break;
                case CMD_AIRPLANE_TOGGLED:
                    if (mSettingsStore.isAirplaneModeOn() &&
                            ! mSettingsStore.isWifiToggleEnabled()) {
                        transitionTo(mQcStaDisablingState);
                    }
                    break;
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    if (! mSettingsStore.isScanAlwaysAvailable()) {
                        mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE);
                        transitionTo(mQcStaDisablingState);
                    }
                    break;
                case CMD_SET_AP:
                    if (msg.arg1 == 1) {
                        if (mStaAndApConcurrency) {
                            /* For STA+SAP concurrency request to start SoftAP is handled in QcApEnablingState. */
                            transitionTo(mQcApEnablingState);
                        } else {
                            // Before starting tethering, turn off supplicant for scan mode
                            mSettingsStore.setWifiSavedState(WifiSettingsStore.WIFI_DISABLED);
                            deferMessage(obtainMessage(msg.what, msg.arg1, 1, msg.obj));
                            transitionTo(mQcStaDisablingState);
                        }
                    }
                    break;
                case CMD_DEFERRED_TOGGLE:
                    if (msg.arg1 != mDeferredEnableSerialNumber) {
                        log("DEFERRED_TOGGLE ignored due to serial mismatch");
                        break;
                    }
                    logd("DEFERRED_TOGGLE handled");
                    sendMessage((Message)(msg.obj));
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        private boolean doDeferEnable(Message msg) {
            long delaySoFar = SystemClock.elapsedRealtime() - mDisabledTimestamp;
            if (delaySoFar >= mReEnableDelayMillis) {
                return false;
            }

            log("WifiController msg " + msg + " deferred for " +
                    (mReEnableDelayMillis - delaySoFar) + "ms");

            // need to defer this action.
            Message deferredMsg = obtainMessage(CMD_DEFERRED_TOGGLE);
            deferredMsg.obj = Message.obtain(msg);
            deferredMsg.arg1 = ++mDeferredEnableSerialNumber;
            sendMessageDelayed(deferredMsg, mReEnableDelayMillis - delaySoFar + DEFER_MARGIN_MS);
            return true;
        }

    }

    /**
     * Only transition out of this state when AP failed to start or AP is stopped.
     */
    class ApEnabledState extends State {
        /**
         * Save the pending state when stopping the AP, so that it will transition
         * to the correct state when AP is stopped.  This is to avoid a possible
         * race condition where the new state might try to update the driver/interface
         * state before AP is completely torn down.
         */
        private State mPendingState = null;

        /**
         * Determine the next state based on the current settings (e.g. saved
         * wifi state).
         */
        private State getNextWifiState() {
            if (mSettingsStore.getWifiSavedState() == WifiSettingsStore.WIFI_ENABLED) {
                if (!mStaAndApConcurrency) {
                    return mDeviceActiveState;
                }
            }

            if (mSettingsStore.isScanAlwaysAvailable()) {
                return mStaDisabledWithScanState;
            }

            return mApStaDisabledState;
        }

        @Override
        public boolean processMessage(Message msg) {
            logStateAndMessage(msg, this);
            switch (msg.what) {
                case CMD_AIRPLANE_TOGGLED:
                    if (mSettingsStore.isAirplaneModeOn()) {
                        if (mStaAndApConcurrency) {
                            transitionTo(mQcApDisablingState);
                        } else {
                            mWifiStateMachine.setHostApRunning(null, false);
                            mPendingState = mApStaDisabledState;
                        }
                    }
                    break;
                case CMD_WIFI_TOGGLED:
                    if (mSettingsStore.isWifiToggleEnabled()) {
                        if (mStaAndApConcurrency) {
                            if (mSettingsStore.isScanAlwaysAvailable()) {
                                mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE);
                                transitionTo(mQcApStaEnabledState);
                            } else {
                                Slog.d(TAG,"ApEnabledState:CMD_WIFI_TOGGLED:->QcApStaEnablingState");
                                mWifiStateMachine.setSupplicantRunning(true);
                                transitionTo(mQcApStaEnablingState);
                            }
                        } else {
                            mWifiStateMachine.setHostApRunning(null, false);
                            mPendingState = mDeviceActiveState;
                        }
                    }
                    break;
                case CMD_SCAN_ALWAYS_MODE_CHANGED:
                    if (!mStaAndApConcurrency) {
                        return NOT_HANDLED;
                    }
                    if (mSettingsStore.isScanAlwaysAvailable()) {
                        mWifiStateMachine.setSupplicantRunning(true);
                        mWifiStateMachine.setOperationalMode(WifiStateMachine.SCAN_ONLY_WITH_WIFI_OFF_MODE);
                    } else {
                        mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE);
                        mWifiStateMachine.setSupplicantRunning(false);
                    }
                    break;
                case CMD_SET_AP:
                    if (msg.arg1 == 0) {
                       /*
                        * For STA+SAP concurrency request to stop SoftAP
                        * is handled in QcApDisablingState.
                        */
                        if (mStaAndApConcurrency) {
                            transitionTo(mQcApDisablingState);
                        } else {
                            mWifiStateMachine.setHostApRunning(null, false);
                            mPendingState = getNextWifiState();
                        }
                    }
                    break;
                case CMD_AP_STOPPED:
                    /* check and enable STA+SAP concurrency if it was disabled because of Dual Sap. */
                    if (mWifiApConfigStore.getDualSapStatus()) {
                        mWifiApConfigStore.setDualSapStatus(false);
                        mWifiStateMachine.setDualSapMode(false);
                        if (mWifiApConfigStore.getStaSapConcurrency())
                            setSoftApStateMachine(mWifiStateMachine.getSoftApStateMachine(), true);
                    }
                    if (mPendingState == null) {
                        /**
                         * Stop triggered internally, either tether notification
                         * timed out or wifi is untethered for some reason.
                         */
                        mPendingState = getNextWifiState();
                    }
                    if (mPendingState == mDeviceActiveState && mDeviceIdle) {
                        checkLocksAndTransitionWhenDeviceIdle();
                    } else {
                        // go ahead and transition because we are not idle or we are not going
                        // to the active state.
                        transitionTo(mPendingState);
                    }
                    break;
                case CMD_EMERGENCY_CALL_STATE_CHANGED:
                case CMD_EMERGENCY_MODE_CHANGED:
                    if (msg.arg1 == 1) {
                        if (mStaAndApConcurrency) {
                            mSoftApStateMachine.setHostApRunning(null, false);
                        } else {
                            mWifiStateMachine.setHostApRunning(null, false);
                        }
                        mPendingState = mEcmState;
                    }
                    break;
                case CMD_AP_START_FAILURE:
                    mPendingState = getNextWifiState();
                    /* check and enable STA+SAP concurrency now */
                    if (mWifiApConfigStore.getStaSapConcurrency()) {
                        mWifiApConfigStore.setDualSapStatus(false);
                        mWifiStateMachine.setDualSapMode(false);
                        setSoftApStateMachine(mWifiStateMachine.getSoftApStateMachine(), true);
                    } else if (mWifiApConfigStore.getApConfiguration().apBand == WifiConfiguration.AP_BAND_DUAL) {
                        mWifiApConfigStore.setDualSapStatus(false);
                        mWifiStateMachine.setDualSapMode(false);
                    }
                    transitionTo(mPendingState);
                    break;
                case CMD_AP_STARTED:
                    // Set dual mode to true if mStaAndApConcurrency is false.
                    if (!mStaAndApConcurrency &&
                        (mWifiApConfigStore.getApConfiguration().apBand == WifiConfiguration.AP_BAND_DUAL))
                        mWifiApConfigStore.setDualSapStatus(true);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class EcmState extends State {
        // we can enter EcmState either because an emergency call started or because
        // emergency callback mode started. This count keeps track of how many such
        // events happened; so we can exit after all are undone

        private int mEcmEntryCount;
        @Override
        public void enter() {
            mWifiStateMachine.setSupplicantRunning(false);
            mWifiStateMachine.clearANQPCache();
            mEcmEntryCount = 1;
        }

        @Override
        public boolean processMessage(Message msg) {
            logStateAndMessage(msg, this);
            if (msg.what == CMD_EMERGENCY_CALL_STATE_CHANGED) {
                if (msg.arg1 == 1) {
                    // nothing to do - just says emergency call started
                    mEcmEntryCount++;
                } else if (msg.arg1 == 0) {
                    // emergency call ended
                    decrementCountAndReturnToAppropriateState();
                }
                return HANDLED;
            } else if (msg.what == CMD_EMERGENCY_MODE_CHANGED) {

                if (msg.arg1 == 1) {
                    // Transitioned into emergency callback mode
                    mEcmEntryCount++;
                } else if (msg.arg1 == 0) {
                    // out of emergency callback mode
                    decrementCountAndReturnToAppropriateState();
                }
                return HANDLED;
            } else {
                return NOT_HANDLED;
            }
        }

        private void decrementCountAndReturnToAppropriateState() {
            boolean exitEcm = false;

            if (mEcmEntryCount == 0) {
                loge("mEcmEntryCount is 0; exiting Ecm");
                exitEcm = true;
            } else if (--mEcmEntryCount == 0) {
                exitEcm = true;
            }

            if (exitEcm) {
                if (mSettingsStore.isWifiToggleEnabled()) {
                    if (mDeviceIdle == false) {
                        transitionTo(mDeviceActiveState);
                    } else {
                        checkLocksAndTransitionWhenDeviceIdle();
                    }
                } else if (mSettingsStore.isScanAlwaysAvailable()) {
                    transitionTo(mStaDisabledWithScanState);
                } else {
                    // Since supplicant already stopped (see enter()), move
                    // directly to ApStaDisabledState.
                    transitionTo(mApStaDisabledState);
                }
            }
        }
    }

    /* Parent: StaEnabledState */
    class DeviceActiveState extends State {
        @Override
        public void enter() {
            mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE);
            mWifiStateMachine.setHighPerfModeEnabled(false);
        }

        @Override
        public boolean processMessage(Message msg) {
            logStateAndMessage(msg, this);
            if (msg.what == CMD_DEVICE_IDLE) {
                checkLocksAndTransitionWhenDeviceIdle();
                // We let default state handle the rest of work
            } else if (msg.what == CMD_USER_PRESENT) {
                // TLS networks can't connect until user unlocks keystore. KeyStore
                // unlocks when the user punches PIN after the reboot. So use this
                // trigger to get those networks connected.
                if (mFirstUserSignOnSeen == false) {
                    mWifiStateMachine.reloadTlsNetworksAndReconnect();
                }
                mFirstUserSignOnSeen = true;
                return HANDLED;
            } else if (msg.what == CMD_RESTART_WIFI) {
                if (!mStaAndApConcurrency) {
                    deferMessage(obtainMessage(CMD_RESTART_WIFI_CONTINUE));
                    transitionTo(mQcStaDisablingState);
                    return HANDLED;
                }
            }
            return NOT_HANDLED;
        }
    }

    /* Parent: StaEnabledState */
    class DeviceInactiveState extends State {
        @Override
        public boolean processMessage(Message msg) {
            logStateAndMessage(msg, this);
            switch (msg.what) {
                case CMD_LOCKS_CHANGED:
                    checkLocksAndTransitionWhenDeviceIdle();
                    updateBatteryWorkSource();
                    return HANDLED;
                case CMD_SCREEN_ON:
                    transitionTo(mDeviceActiveState);
                    // More work in default state
                    return NOT_HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    /* Parent: DeviceInactiveState. Device is inactive, but an app is holding a scan only lock. */
    class ScanOnlyLockHeldState extends State {
        @Override
        public void enter() {
            mWifiStateMachine.setOperationalMode(WifiStateMachine.SCAN_ONLY_MODE);
        }
    }

    /* Parent: DeviceInactiveState. Device is inactive, but an app is holding a full lock. */
    class FullLockHeldState extends State {
        @Override
        public void enter() {
            mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE);
            mWifiStateMachine.setHighPerfModeEnabled(false);
        }
    }

    /* Parent: DeviceInactiveState. Device is inactive, but an app is holding a high perf lock. */
    class FullHighPerfLockHeldState extends State {
        @Override
        public void enter() {
            mWifiStateMachine.setOperationalMode(WifiStateMachine.CONNECT_MODE);
            mWifiStateMachine.setHighPerfModeEnabled(true);
        }
    }

    /* Parent: DeviceInactiveState. Device is inactive and no app is holding a wifi lock. */
    class NoLockHeldState extends State {
        @Override
        public void enter() {
            mWifiStateMachine.setOperationalMode(WifiStateMachine.DISABLED_MODE);
        }
    }

    private void checkLocksAndTransitionWhenDeviceIdle() {
        switch (mWifiLockManager.getStrongestLockMode()) {
            case WIFI_MODE_NO_LOCKS_HELD:
                if (mSettingsStore.isScanAlwaysAvailable()) {
                    transitionTo(mScanOnlyLockHeldState);
                } else {
                    transitionTo(mNoLockHeldState);
                }
                break;
            case WIFI_MODE_FULL:
                transitionTo(mFullLockHeldState);
                break;
            case WIFI_MODE_FULL_HIGH_PERF:
                transitionTo(mFullHighPerfLockHeldState);
                break;
            case WIFI_MODE_SCAN_ONLY:
                transitionTo(mScanOnlyLockHeldState);
                break;
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);

        pw.println("mScreenOff " + mScreenOff);
        pw.println("mDeviceIdle " + mDeviceIdle);
        pw.println("mPluggedType " + mPluggedType);
        pw.println("mIdleMillis " + mIdleMillis);
        pw.println("mSleepPolicy " + mSleepPolicy);
    }
}
