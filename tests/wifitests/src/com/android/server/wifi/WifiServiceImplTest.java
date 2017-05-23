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

import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.provider.Settings.Secure.LOCATION_MODE_HIGH_ACCURACY;
import static android.provider.Settings.Secure.LOCATION_MODE_OFF;

import static com.android.server.wifi.WifiController.CMD_SET_AP;
import static com.android.server.wifi.WifiController.CMD_WIFI_TOGGLED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.*;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.net.IpConfiguration;
import android.net.wifi.ScanSettings;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.UserManager;
import android.os.WorkSource;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.internal.util.AsyncChannel;
import com.android.server.wifi.WifiServiceImpl.LocalOnlyRequestorCallback;
import com.android.server.wifi.util.WifiAsyncChannel;
import com.android.server.wifi.util.WifiPermissionsUtil;


import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Unit tests for {@link WifiServiceImpl}.
 *
 * Note: this is intended to build up over time and will not immediately cover the entire file.
 */
@SmallTest
public class WifiServiceImplTest {

    private static final String TAG = "WifiServiceImplTest";
    private static final String SCAN_PACKAGE_NAME = "scanPackage";
    private static final String WHITE_LIST_SCAN_PACKAGE_NAME = "whiteListScanPackage";
    private static final int DEFAULT_VERBOSE_LOGGING = 0;
    private static final long WIFI_BACKGROUND_SCAN_INTERVAL = 10000;
    private static final String ANDROID_SYSTEM_PACKAGE = "android";
    private static final String TEST_PACKAGE_NAME = "TestPackage";
    private static final String SETTINGS_PACKAGE_NAME = "com.android.settings";
    private static final String SYSUI_PACKAGE_NAME = "com.android.systemui";
    private static final int TEST_UID = 6789;

    private WifiServiceImpl mWifiServiceImpl;
    private TestLooper mLooper;
    private PowerManager mPowerManager;
    private Handler mHandler;
    private Messenger mAppMessenger;
    private int mUid;

    @Mock Context mContext;
    @Mock WifiInjector mWifiInjector;
    @Mock Clock mClock;
    @Mock WifiController mWifiController;
    @Mock WifiTrafficPoller mWifiTrafficPoller;
    @Mock WifiStateMachine mWifiStateMachine;
    @Mock HandlerThread mHandlerThread;
    @Mock AsyncChannel mAsyncChannel;
    @Mock Resources mResources;
    @Mock FrameworkFacade mFrameworkFacade;
    @Mock WifiLockManager mLockManager;
    @Mock WifiMulticastLockManager mWifiMulticastLockManager;
    @Mock WifiLastResortWatchdog mWifiLastResortWatchdog;
    @Mock WifiBackupRestore mWifiBackupRestore;
    @Mock WifiMetrics mWifiMetrics;
    @Mock WifiPermissionsUtil mWifiPermissionsUtil;
    @Mock WifiSettingsStore mSettingsStore;
    @Mock ContentResolver mContentResolver;
    @Mock UserManager mUserManager;
    @Mock WifiConfiguration mApConfig;
    @Mock ActivityManager mActivityManager;
    @Mock AppOpsManager mAppOpsManager;
    @Mock IBinder mAppBinder;
    @Mock WifiNotificationController mWifiNotificationController;
    @Mock LocalOnlyHotspotRequestInfo mRequestInfo;

    @Spy FakeWifiLog mLog;

    private class WifiAsyncChannelTester {
        private static final String TAG = "WifiAsyncChannelTester";
        public static final int CHANNEL_STATE_FAILURE = -1;
        public static final int CHANNEL_STATE_DISCONNECTED = 0;
        public static final int CHANNEL_STATE_HALF_CONNECTED = 1;
        public static final int CHANNEL_STATE_FULLY_CONNECTED = 2;

        private int mState = CHANNEL_STATE_DISCONNECTED;
        private WifiAsyncChannel mChannel;
        private WifiLog mAsyncTestLog;

        WifiAsyncChannelTester(WifiInjector wifiInjector) {
            mAsyncTestLog = wifiInjector.makeLog(TAG);
        }

        public int getChannelState() {
            return mState;
        }

        public void connect(final Looper looper, final Messenger messenger,
                final Handler incomingMessageHandler) {
            assertEquals("AsyncChannel must be in disconnected state",
                    CHANNEL_STATE_DISCONNECTED, mState);
            mChannel = new WifiAsyncChannel(TAG);
            mChannel.setWifiLog(mLog);
            Handler handler = new Handler(mLooper.getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                            if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                                mChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                                mState = CHANNEL_STATE_HALF_CONNECTED;
                            } else {
                                mState = CHANNEL_STATE_FAILURE;
                            }
                            break;
                        case AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED:
                            mState = CHANNEL_STATE_FULLY_CONNECTED;
                            break;
                        case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                            mState = CHANNEL_STATE_DISCONNECTED;
                            break;
                        default:
                            incomingMessageHandler.handleMessage(msg);
                            break;
                    }
                }
            };
            mChannel.connect(null, handler, messenger);
        }
    }

    @Before public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLooper = new TestLooper();
        mHandler = new Handler(mLooper.getLooper());
        mAppMessenger = new Messenger(mHandler);

        when(mRequestInfo.getUid()).thenReturn(mUid);
        when(mWifiInjector.getUserManager()).thenReturn(mUserManager);
        when(mWifiInjector.getWifiController()).thenReturn(mWifiController);
        when(mWifiInjector.getWifiMetrics()).thenReturn(mWifiMetrics);
        when(mWifiInjector.getWifiStateMachine()).thenReturn(mWifiStateMachine);
        when(mWifiStateMachine.syncInitialize(any())).thenReturn(true);
        when(mWifiInjector.getWifiServiceHandlerThread()).thenReturn(mHandlerThread);
        when(mHandlerThread.getLooper()).thenReturn(mLooper.getLooper());
        when(mContext.getResources()).thenReturn(mResources);
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        doNothing().when(mFrameworkFacade).registerContentObserver(eq(mContext), any(),
                anyBoolean(), any());
        when(mContext.getSystemService(Context.ACTIVITY_SERVICE)).thenReturn(mActivityManager);
        when(mContext.getSystemService(Context.APP_OPS_SERVICE)).thenReturn(mAppOpsManager);
        when(mFrameworkFacade.getLongSetting(
                eq(mContext),
                eq(Settings.Global.WIFI_SCAN_BACKGROUND_THROTTLE_INTERVAL_MS),
                anyLong()))
                .thenReturn(WIFI_BACKGROUND_SCAN_INTERVAL);
        when(mFrameworkFacade.getStringSetting(
                eq(mContext),
                eq(Settings.Global.WIFI_SCAN_BACKGROUND_THROTTLE_PACKAGE_WHITELIST)))
                .thenReturn(WHITE_LIST_SCAN_PACKAGE_NAME);
        IPowerManager powerManagerService = mock(IPowerManager.class);
        mPowerManager = new PowerManager(mContext, powerManagerService, new Handler());
        when(mContext.getSystemServiceName(PowerManager.class)).thenReturn(Context.POWER_SERVICE);
        when(mContext.getSystemService(PowerManager.class)).thenReturn(mPowerManager);
        WifiAsyncChannel wifiAsyncChannel = new WifiAsyncChannel("WifiServiceImplTest");
        wifiAsyncChannel.setWifiLog(mLog);
        when(mFrameworkFacade.makeWifiAsyncChannel(anyString())).thenReturn(wifiAsyncChannel);
        when(mWifiInjector.getFrameworkFacade()).thenReturn(mFrameworkFacade);
        when(mWifiInjector.getWifiLockManager()).thenReturn(mLockManager);
        when(mWifiInjector.getWifiMulticastLockManager()).thenReturn(mWifiMulticastLockManager);
        when(mWifiInjector.getWifiLastResortWatchdog()).thenReturn(mWifiLastResortWatchdog);
        when(mWifiInjector.getWifiBackupRestore()).thenReturn(mWifiBackupRestore);
        when(mWifiInjector.makeLog(anyString())).thenReturn(mLog);
        WifiTrafficPoller wifiTrafficPoller = new WifiTrafficPoller(mContext,
                mLooper.getLooper(), "mockWlan");
        when(mWifiInjector.getWifiTrafficPoller()).thenReturn(wifiTrafficPoller);
        when(mWifiInjector.getWifiPermissionsUtil()).thenReturn(mWifiPermissionsUtil);
        when(mWifiInjector.getWifiSettingsStore()).thenReturn(mSettingsStore);
        when(mWifiInjector.getClock()).thenReturn(mClock);
        when(mWifiInjector.getWifiNotificationController()).thenReturn(mWifiNotificationController);
        mWifiServiceImpl = new WifiServiceImpl(mContext, mWifiInjector, mAsyncChannel);
        mWifiServiceImpl.setWifiHandlerLogForTest(mLog);
    }

    @Test
    public void testRemoveNetworkUnknown() {
        assertFalse(mWifiServiceImpl.removeNetwork(-1));
    }

    @Test
    public void testAsyncChannelHalfConnected() {
        WifiAsyncChannelTester channelTester = new WifiAsyncChannelTester(mWifiInjector);
        Handler handler = mock(Handler.class);
        TestLooper looper = new TestLooper();
        channelTester.connect(looper.getLooper(), mWifiServiceImpl.getWifiServiceMessenger(),
                handler);
        mLooper.dispatchAll();
        assertEquals("AsyncChannel must be half connected",
                WifiAsyncChannelTester.CHANNEL_STATE_HALF_CONNECTED,
                channelTester.getChannelState());
    }

    /**
     * Tests the isValid() check for StaticIpConfigurations, ensuring that configurations with null
     * ipAddress are rejected, and configurations with ipAddresses are valid.
     */
    @Test
    public void testStaticIpConfigurationValidityCheck() {
        WifiConfiguration conf = WifiConfigurationTestUtil.createOpenNetwork();
        IpConfiguration ipConf =
                WifiConfigurationTestUtil.createStaticIpConfigurationWithStaticProxy();
        conf.setIpConfiguration(ipConf);
        // Ensure staticIpConfiguration with IP Address is valid
        assertTrue(mWifiServiceImpl.isValid(conf));
        ipConf.staticIpConfiguration.ipAddress = null;
        // Ensure staticIpConfiguration with null IP Address it is not valid
        conf.setIpConfiguration(ipConf);
        assertFalse(mWifiServiceImpl.isValid(conf));
    }

    /**
     * Ensure WifiMetrics.dump() is the only dump called when 'dumpsys wifi WifiMetricsProto' is
     * called. This is required to support simple metrics collection via dumpsys
     */
    @Test
    public void testWifiMetricsDump() {
        mWifiServiceImpl.dump(new FileDescriptor(), new PrintWriter(new StringWriter()),
                new String[]{mWifiMetrics.PROTO_DUMP_ARG});
        verify(mWifiMetrics)
                .dump(any(FileDescriptor.class), any(PrintWriter.class), any(String[].class));
        verify(mWifiStateMachine, never())
                .dump(any(FileDescriptor.class), any(PrintWriter.class), any(String[].class));
    }


    /**
     * Ensure WifiServiceImpl.dump() doesn't throw an NPE when executed with null args
     */
    @Test
    public void testDumpNullArgs() {
        mWifiServiceImpl.dump(new FileDescriptor(), new PrintWriter(new StringWriter()), null);
    }

    /**
     * Verify that wifi can be enabled by a caller with WIFI_STATE_CHANGE permission when wifi is
     * off (no hotspot, no airplane mode).
     */
    @Test
    public void testSetWifiEnabledSuccess() throws Exception {
        when(mWifiStateMachine.syncGetWifiApState()).thenReturn(WifiManager.WIFI_AP_STATE_DISABLED);
        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));
        verify(mWifiController).sendMessage(eq(CMD_WIFI_TOGGLED));
    }

    /**
     * Verify that the CMD_TOGGLE_WIFI message won't be sent if wifi is already on.
     */
    @Test
    public void testSetWifiEnabledNoToggle() throws Exception {
        when(mWifiStateMachine.syncGetWifiApState()).thenReturn(WifiManager.WIFI_AP_STATE_DISABLED);
        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(false);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));
        verify(mWifiController, never()).sendMessage(eq(CMD_WIFI_TOGGLED));
    }

    /**
     * Verify a SecurityException is thrown if a caller does not have the correct permission to
     * toggle wifi.
     */
    @Test(expected = SecurityException.class)
    public void testSetWifiEnableWithoutPermission() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.CHANGE_WIFI_STATE),
                                                eq("WifiService"));
        mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true);
        verify(mWifiStateMachine, never()).syncGetWifiApState();
    }

    /**
     * Verify that a call from Settings can enable wifi if we are in softap mode.
     */
    @Test
    public void testSetWifiEnabledFromSettingsWhenApEnabled() throws Exception {
        when(mWifiStateMachine.syncGetWifiApState()).thenReturn(WifiManager.WIFI_AP_STATE_ENABLED);
        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);
        assertTrue(mWifiServiceImpl.setWifiEnabled(SETTINGS_PACKAGE_NAME, true));
        verify(mWifiController).sendMessage(eq(CMD_WIFI_TOGGLED));
    }

    /**
     * Verify that a call from SysUI can enable wifi if we are in softap mode.
     */
    @Test
    public void testSetWifiEnabledFromSysUiWhenApEnabled() throws Exception {
        when(mWifiStateMachine.syncGetWifiApState()).thenReturn(WifiManager.WIFI_AP_STATE_ENABLED);
        when(mSettingsStore.handleWifiToggled(eq(true))).thenReturn(true);
        assertTrue(mWifiServiceImpl.setWifiEnabled(SYSUI_PACKAGE_NAME, true));
        verify(mWifiController).sendMessage(eq(CMD_WIFI_TOGGLED));
    }

    /**
     * Verify that a call from an app cannot enable wifi if we are in softap mode.
     */
    @Test
    public void testSetWifiEnabledFromAppFailsWhenApEnabled() throws Exception {
        when(mWifiStateMachine.syncGetWifiApState()).thenReturn(WifiManager.WIFI_AP_STATE_ENABLED);
        assertFalse(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, true));
        verify(mSettingsStore, never()).handleWifiToggled(anyBoolean());
        verify(mWifiController, never()).sendMessage(eq(CMD_WIFI_TOGGLED));
    }

    /**
     * Verify that wifi can be disabled by a caller with WIFI_STATE_CHANGE permission when wifi is
     * on.
     */
    @Test
    public void testSetWifiDisabledSuccess() throws Exception {
        when(mWifiStateMachine.syncGetWifiApState()).thenReturn(WifiManager.WIFI_AP_STATE_DISABLED);
        when(mSettingsStore.handleWifiToggled(eq(false))).thenReturn(true);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false));
        verify(mWifiController).sendMessage(eq(CMD_WIFI_TOGGLED));
    }

    /**
     * Verify that CMD_TOGGLE_WIFI message won't be sent if wifi is already off.
     */
    @Test
    public void testSetWifiDisabledNoToggle() throws Exception {
        when(mWifiStateMachine.syncGetWifiApState()).thenReturn(WifiManager.WIFI_AP_STATE_DISABLED);
        when(mSettingsStore.handleWifiToggled(eq(false))).thenReturn(false);
        assertTrue(mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false));
        verify(mWifiController, never()).sendMessage(eq(CMD_WIFI_TOGGLED));
    }

    /**
     * Verify a SecurityException is thrown if a caller does not have the correct permission to
     * toggle wifi.
     */
    @Test(expected = SecurityException.class)
    public void testSetWifiDisabledWithoutPermission() throws Exception {
        when(mWifiStateMachine.syncGetWifiApState()).thenReturn(WifiManager.WIFI_AP_STATE_DISABLED);
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.CHANGE_WIFI_STATE),
                                                eq("WifiService"));
        mWifiServiceImpl.setWifiEnabled(TEST_PACKAGE_NAME, false);
    }

    /**
     * Ensure unpermitted callers cannot write the SoftApConfiguration.
     *
     * @throws SecurityException
     */
    @Test(expected = SecurityException.class)
    public void testSetWifiApConfigurationNotSavedWithoutPermission() {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(false);
        WifiConfiguration apConfig = new WifiConfiguration();
        mWifiServiceImpl.setWifiApConfiguration(apConfig);
        verify(mWifiStateMachine, never()).setWifiApConfiguration(eq(apConfig));
    }

    /**
     * Ensure softap config is written when the caller has the correct permission.
     */
    @Test
    public void testSetWifiApConfigurationSuccess() {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        WifiConfiguration apConfig = new WifiConfiguration();
        mWifiServiceImpl.setWifiApConfiguration(apConfig);
        verify(mWifiStateMachine).setWifiApConfiguration(eq(apConfig));
    }

    /**
     * Ensure that a null config does not overwrite the saved ap config.
     */
    @Test
    public void testSetWifiApConfigurationNullConfigNotSaved() {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        mWifiServiceImpl.setWifiApConfiguration(null);
        verify(mWifiStateMachine, never()).setWifiApConfiguration(isNull(WifiConfiguration.class));
    }

    /**
     * Ensure unpermitted callers are not able to retrieve the softap config.
     *
     * @throws SecurityException
     */
    @Test(expected = SecurityException.class)
    public void testGetWifiApConfigurationNotReturnedWithoutPermission() {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(false);
        mWifiServiceImpl.getWifiApConfiguration();
        verify(mWifiStateMachine, never()).syncGetWifiApConfiguration();
    }

    /**
     * Ensure permitted callers are able to retrieve the softap config.
     */
    @Test
    public void testGetWifiApConfigurationSuccess() {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        WifiConfiguration apConfig = new WifiConfiguration();
        when(mWifiStateMachine.syncGetWifiApConfiguration()).thenReturn(apConfig);
        assertEquals(apConfig, mWifiServiceImpl.getWifiApConfiguration());
    }

    /**
     * Make sure we do not start wifi if System services have to be restarted to decrypt the device.
     */
    @Test
    public void testWifiControllerDoesNotStartWhenDeviceTriggerResetMainAtBoot() {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(true);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        verify(mWifiController, never()).start();
    }

    /**
     * Make sure we do start WifiController (wifi disabled) if the device is already decrypted.
     */
    @Test
    public void testWifiControllerStartsWhenDeviceIsDecryptedAtBootWithWifiDisabled() {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(false);
        mWifiServiceImpl.checkAndStartWifi();
        verify(mWifiController).start();
        verify(mWifiController, never()).sendMessage(CMD_WIFI_TOGGLED);
    }

    /**
     * Make sure we do start WifiController (wifi enabled) if the device is already decrypted.
     */
    @Test
    public void testWifiFullyStartsWhenDeviceIsDecryptedAtBootWithWifiEnabled() {
        when(mFrameworkFacade.inStorageManagerCryptKeeperBounce()).thenReturn(false);
        when(mSettingsStore.handleWifiToggled(true)).thenReturn(true);
        when(mSettingsStore.isWifiToggleEnabled()).thenReturn(true);
        when(mWifiStateMachine.syncGetWifiState()).thenReturn(WIFI_STATE_DISABLED);
        when(mWifiStateMachine.syncGetWifiApState()).thenReturn(WifiManager.WIFI_AP_STATE_DISABLED);
        when(mContext.getPackageName()).thenReturn(ANDROID_SYSTEM_PACKAGE);
        mWifiServiceImpl.checkAndStartWifi();
        verify(mWifiController).start();
        verify(mWifiController).sendMessage(CMD_WIFI_TOGGLED);
    }

    /**
     * Verify setWifiApEnabled works with the correct permissions and a null config.
     */
    @Test
    public void testSetWifiApEnabledWithProperPermissionsWithNullConfig() {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        when(mUserManager.hasUserRestriction(eq(UserManager.DISALLOW_CONFIG_TETHERING)))
                .thenReturn(false);
        mWifiServiceImpl.setWifiApEnabled(null, true);
        verify(mWifiController).sendMessage(eq(CMD_SET_AP), eq(1), eq(0), eq(null));
    }

    /**
     * Verify setWifiApEnabled works with correct permissions and a valid config.
     *
     * TODO: should really validate that ap configs have a set of basic config settings b/37280779
     */
    @Test
    public void testSetWifiApEnabledWithProperPermissionsWithValidConfig() {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        when(mUserManager.hasUserRestriction(eq(UserManager.DISALLOW_CONFIG_TETHERING)))
                .thenReturn(false);
        WifiConfiguration apConfig = new WifiConfiguration();
        mWifiServiceImpl.setWifiApEnabled(apConfig, true);
        verify(mWifiController).sendMessage(eq(CMD_SET_AP), eq(1), eq(0), eq(apConfig));
    }

    /**
     * Verify setWifiApEnabled when disabling softap with correct permissions sends the correct
     * message to WifiController.
     */
    @Test
    public void testSetWifiApEnabledFalseWithProperPermissionsWithNullConfig() {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        when(mUserManager.hasUserRestriction(eq(UserManager.DISALLOW_CONFIG_TETHERING)))
                .thenReturn(false);
        mWifiServiceImpl.setWifiApEnabled(null, false);
        verify(mWifiController).sendMessage(eq(CMD_SET_AP), eq(0), eq(0), eq(null));
    }

    /**
     * setWifiApEnabled should fail if the provided config is not valid.
     */
    @Test
    public void testSetWifiApEnabledWithProperPermissionInvalidConfigFails() {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        when(mUserManager.hasUserRestriction(eq(UserManager.DISALLOW_CONFIG_TETHERING)))
                .thenReturn(false);
        // mApConfig is a mock and the values are not set - triggering the invalid config.  Testing
        // will be improved when we actually do test softap configs in b/37280779
        mWifiServiceImpl.setWifiApEnabled(mApConfig, true);
        verify(mWifiController, never()).sendMessage(eq(CMD_SET_AP), eq(1), eq(0), eq(mApConfig));
    }

    /**
     * setWifiApEnabled should throw a security exception when the caller does not have the correct
     * permissions.
     */
    @Test(expected = SecurityException.class)
    public void testSetWifiApEnabledThrowsSecurityExceptionWithoutConfigOverridePermission()
            throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.CHANGE_WIFI_STATE),
                        eq("WifiService"));
        mWifiServiceImpl.setWifiApEnabled(null, true);
    }

    /**
     * setWifiApEnabled should throw a SecurityException when disallow tethering is set for the
     * user.
     */
    @Test(expected = SecurityException.class)
    public void testSetWifiApEnabledThrowsSecurityExceptionWithDisallowTethering()
            throws Exception {
        when(mWifiPermissionsUtil.checkConfigOverridePermission(anyInt())).thenReturn(true);
        when(mUserManager.hasUserRestriction(eq(UserManager.DISALLOW_CONFIG_TETHERING)))
                .thenReturn(true);
        mWifiServiceImpl.setWifiApEnabled(null, true);

    }

    /**
     * Verify caller with proper permission can call startSoftAp.
     */
    @Test
    public void testStartSoftApWithPermissionsAndNullConfig() {
        boolean result = mWifiServiceImpl.startSoftAp(null);
        assertTrue(result);
        verify(mWifiController).sendMessage(eq(CMD_SET_AP), eq(1), eq(0), eq(null));
    }

    /**
     * Verify caller with proper permissions but an invalid config does not start softap.
     */
    @Test
    public void testStartSoftApWithPermissionsAndInvalidConfig() {
        boolean result = mWifiServiceImpl.startSoftAp(mApConfig);
        assertFalse(result);
        verifyZeroInteractions(mWifiController);
    }

    /**
     * Verify caller with proper permission and valid config does start softap.
     */
    @Test
    public void testStartSoftApWithPermissionsAndValidConfig() {
        WifiConfiguration config = new WifiConfiguration();
        boolean result = mWifiServiceImpl.startSoftAp(config);
        assertTrue(result);
        verify(mWifiController).sendMessage(eq(CMD_SET_AP), eq(1), eq(0), eq(config));
    }

    /**
     * Verify a SecurityException is thrown when a caller without the correct permission attempts to
     * start softap.
     */
    @Test(expected = SecurityException.class)
    public void testStartSoftApWithoutPermissionThrowsException() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_STACK),
                                                eq("WifiService"));
        mWifiServiceImpl.startSoftAp(null);
    }

    /**
     * Verify caller with proper permission can call stopSoftAp.
     */
    @Test
    public void testStopSoftApWithPermissions() {
        boolean result = mWifiServiceImpl.stopSoftAp();
        assertTrue(result);
        verify(mWifiController).sendMessage(eq(CMD_SET_AP), eq(0), eq(0));
    }

    /**
     * Verify SecurityException is thrown when a caller without the correct permission attempts to
     * stop softap.
     */
    @Test(expected = SecurityException.class)
    public void testStopSoftApWithoutPermissionThrowsException() throws Exception {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.NETWORK_STACK),
                                                eq("WifiService"));
        mWifiServiceImpl.stopSoftAp();
    }

    /**
     * Ensure foreground apps can always do wifi scans.
     */
    @Test
    public void testWifiScanStartedForeground() {
        when(mActivityManager.getPackageImportance(SCAN_PACKAGE_NAME)).thenReturn(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE);
        mWifiServiceImpl.startScan(null, null, SCAN_PACKAGE_NAME);
        verify(mWifiStateMachine).startScan(
                anyInt(), anyInt(), (ScanSettings) eq(null), any(WorkSource.class));
    }

    /**
     * Ensure background apps get throttled when the previous scan is too close.
     */
    @Test
    public void testWifiScanBackgroundThrottled() {
        when(mActivityManager.getPackageImportance(SCAN_PACKAGE_NAME)).thenReturn(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        long startMs = 1000;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(startMs);
        mWifiServiceImpl.startScan(null, null, SCAN_PACKAGE_NAME);
        verify(mWifiStateMachine).startScan(
                anyInt(), anyInt(), (ScanSettings) eq(null), any(WorkSource.class));

        when(mClock.getElapsedSinceBootMillis()).thenReturn(
                startMs + WIFI_BACKGROUND_SCAN_INTERVAL - 1000);
        mWifiServiceImpl.startScan(null, null, SCAN_PACKAGE_NAME);
        verify(mWifiStateMachine, times(1)).startScan(
                anyInt(), anyInt(), (ScanSettings) eq(null), any(WorkSource.class));
    }

    /**
     * Ensure background apps can do wifi scan when the throttle interval reached.
     */
    @Test
    public void testWifiScanBackgroundNotThrottled() {
        when(mActivityManager.getPackageImportance(SCAN_PACKAGE_NAME)).thenReturn(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        long startMs = 1000;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(startMs);
        mWifiServiceImpl.startScan(null, null, SCAN_PACKAGE_NAME);
        verify(mWifiStateMachine).startScan(
                anyInt(), eq(0), (ScanSettings) eq(null), any(WorkSource.class));

        when(mClock.getElapsedSinceBootMillis()).thenReturn(
                startMs + WIFI_BACKGROUND_SCAN_INTERVAL + 1000);
        mWifiServiceImpl.startScan(null, null, SCAN_PACKAGE_NAME);
        verify(mWifiStateMachine).startScan(
                anyInt(), eq(1), (ScanSettings) eq(null), any(WorkSource.class));
    }

    /**
     * Ensure background apps can do wifi scan when the throttle interval reached.
     */
    @Test
    public void testWifiScanBackgroundWhiteListed() {
        when(mActivityManager.getPackageImportance(WHITE_LIST_SCAN_PACKAGE_NAME)).thenReturn(
                ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED);
        long startMs = 1000;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(startMs);
        mWifiServiceImpl.startScan(null, null, WHITE_LIST_SCAN_PACKAGE_NAME);
        verify(mWifiStateMachine).startScan(
                anyInt(), anyInt(), (ScanSettings) eq(null), any(WorkSource.class));

        when(mClock.getElapsedSinceBootMillis()).thenReturn(
                startMs + WIFI_BACKGROUND_SCAN_INTERVAL - 1000);
        mWifiServiceImpl.startScan(null, null, WHITE_LIST_SCAN_PACKAGE_NAME);
        verify(mWifiStateMachine, times(2)).startScan(
                anyInt(), anyInt(), (ScanSettings) eq(null), any(WorkSource.class));
    }

    /**
     * Verify that the call to startLocalOnlyHotspot throws the UnsupportedOperationException
     * until the implementation is complete.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testStartLocalOnlyHotspotNotSupported() {
        // allow test to proceed without a permission check failure
        when(mSettingsStore.getLocationModeSetting(mContext))
                .thenReturn(LOCATION_MODE_HIGH_ACCURACY);
        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING))
                .thenReturn(false);
        when(mWifiStateMachine.syncGetWifiApState()).thenReturn(WifiManager.WIFI_AP_STATE_DISABLED);
        mWifiServiceImpl.startLocalOnlyHotspot(mAppMessenger, mAppBinder);
    }

    /**
     * Verify that a call to startLocalOnlyHotspot throws a SecurityException if the caller does not
     * have the CHANGE_WIFI_STATE permission.
     */
    @Test(expected = SecurityException.class)
    public void testStartLocalOnlyHotspotThrowsSecurityExceptionWithoutCorrectPermission() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.CHANGE_WIFI_STATE),
                                                eq("WifiService"));
        mWifiServiceImpl.startLocalOnlyHotspot(mAppMessenger, mAppBinder);
    }

    /**
     * Verify that a call to startLocalOnlyHotspot throws a SecurityException if the caller does not
     * have Location permission.
     */
    @Test(expected = SecurityException.class)
    public void testStartLocalOnlyHotspotThrowsSecurityExceptionWithoutLocationPermission() {
        when(mContext.getOpPackageName()).thenReturn(TEST_PACKAGE_NAME);
        doThrow(new SecurityException())
                .when(mWifiPermissionsUtil).enforceLocationPermission(eq(TEST_PACKAGE_NAME),
                                                                      anyInt());
        mWifiServiceImpl.startLocalOnlyHotspot(mAppMessenger, mAppBinder);
    }

    /**
     * Verify that a call to startLocalOnlyHotspot throws a SecurityException if Location mode is
     * disabled.
     */
    @Test(expected = SecurityException.class)
    public void testStartLocalOnlyHotspotThrowsSecurityExceptionWithoutLocationEnabled() {
        when(mSettingsStore.getLocationModeSetting(mContext)).thenReturn(LOCATION_MODE_OFF);
        mWifiServiceImpl.startLocalOnlyHotspot(mAppMessenger, mAppBinder);
    }

    /**
     * Only start LocalOnlyHotspot if we are not tethering.
     */
    @Test
    public void testHotspotDoesNotStartWhenAlreadyTethering() {
        when(mSettingsStore.getLocationModeSetting(mContext))
                            .thenReturn(LOCATION_MODE_HIGH_ACCURACY);
        when(mWifiStateMachine.syncGetWifiApState()).thenReturn(WifiManager.WIFI_AP_STATE_ENABLED);
        int returnCode = mWifiServiceImpl.startLocalOnlyHotspot(mAppMessenger, mAppBinder);
        assertEquals(ERROR_INCOMPATIBLE_MODE, returnCode);
    }

    /**
     * Only start LocalOnlyHotspot if admin setting does not disallow tethering.
     */
    @Test
    public void testHotspotDoesNotStartWhenTetheringDisallowed() {
        when(mSettingsStore.getLocationModeSetting(mContext))
                .thenReturn(LOCATION_MODE_HIGH_ACCURACY);
        when(mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING))
                .thenReturn(true);
        when(mWifiStateMachine.syncGetWifiApState()).thenReturn(WifiManager.WIFI_AP_STATE_ENABLED);
        int returnCode = mWifiServiceImpl.startLocalOnlyHotspot(mAppMessenger, mAppBinder);
        assertEquals(ERROR_TETHERING_DISALLOWED, returnCode);
    }

    /**
     * Verify that the call to stopLocalOnlyHotspot throws the UnsupportedOperationException until
     * the implementation is complete.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testStopLocalOnlyHotspotNotSupported() {
        // allow test to proceed without a permission check failure
        mWifiServiceImpl.stopLocalOnlyHotspot();
    }

    /**
     * Verify that a call to stopLocalOnlyHotspot throws a SecurityException if the caller does not
     * have the CHANGE_WIFI_STATE permission.
     */
    @Test(expected = SecurityException.class)
    public void testStopLocalOnlyHotspotThrowsSecurityExceptionWithoutCorrectPermission() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(eq(android.Manifest.permission.CHANGE_WIFI_STATE),
                                                eq("WifiService"));
        mWifiServiceImpl.stopLocalOnlyHotspot();
    }

    /**
     * Verify that WifiServiceImpl does not send the stop ap message if there were no
     * pending LOHS requests upon a binder death callback.
     *
     * TODO: add a test verifying it is not called when there are remaining requests.
     * TODO: add a test verifying it is called when the last request was removed.
     */
    @Test
    public void testServiceImplNotCalledWhenBinderDeathTriggeredNoRequests() {
        LocalOnlyRequestorCallback binderDeathCallback =
                mWifiServiceImpl.new LocalOnlyRequestorCallback();

        binderDeathCallback.onLocalOnlyHotspotRequestorDeath(mRequestInfo);
        verify(mWifiController, never()).sendMessage(eq(CMD_SET_AP), eq(0), eq(0));
    }

    /**
     * Verify that the call to startWatchLocalOnlyHotspot throws the UnsupportedOperationException
     * until the implementation is complete.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testStartWatchLocalOnlyHotspotNotSupported() {
        mWifiServiceImpl.startWatchLocalOnlyHotspot(mAppMessenger, mAppBinder);
    }

    /**
     * Verify that the call to stopWatchLocalOnlyHotspot throws the UnsupportedOperationException
     * until the implementation is complete.
     */
    @Test(expected = UnsupportedOperationException.class)
    public void testStopWatchLocalOnlyHotspotNotSupported() {
        mWifiServiceImpl.stopWatchLocalOnlyHotspot();
    }
}
