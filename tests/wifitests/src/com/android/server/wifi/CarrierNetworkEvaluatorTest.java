/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.Process;
import android.util.LocalLog;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.util.ScanResultUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for CarrierNeteworkEvaluator
 */
@SmallTest
public class CarrierNetworkEvaluatorTest {
    private static final String CARRIER1_SSID = "carrier1";
    private static final String CARRIER2_SSID = "carrier2";
    private static final String CARRIER_SAVED_SSID = "carrier3-saved";
    private static final String CARRIER_SAVED_EPH_SSID = "carrier4-saved-ephemeral";
    private static final String NON_CARRIER_SSID = "non-carrier";

    private static final int CARRIER1_NET_ID = 1;
    private static final int CARRIER2_NET_ID = 2;
    private static final int CARRIER_SAVED_NET_ID = 3;
    private static final int CARRIER_SAVED_EPH_NET_ID = 4;
    private static final int NON_CARRIER_NET_ID = 5;

    private CarrierNetworkEvaluator mDut;

    @Mock private WifiConfigManager mWifiConfigManager;
    @Mock private CarrierNetworkConfig mCarrierNetworkConfig;
    @Mock private LocalLog mLocalLog;
    @Mock private NetworkUpdateResult mNetworkUpdateResult;
    @Mock private Clock mClock;
    @Mock private WifiNetworkSelector.NetworkEvaluator.OnConnectableListener mConnectableListener;

    private ArgumentCaptor<ScanDetail> mScanDetailCaptor = ArgumentCaptor.forClass(
            ScanDetail.class);
    private ArgumentCaptor<WifiConfiguration> mWifiConfigCaptor = ArgumentCaptor.forClass(
            WifiConfiguration.class);

    private class GetConfiguredNetworkForScanDetailsAnswer implements Answer<WifiConfiguration> {
        private Map<String, WifiConfiguration> mConfig = new HashMap<>();

        public void addConfig(ScanDetail scanDetail, WifiConfiguration config) {
            mConfig.put(scanDetail.toKeyString(), config);
        }

        @Override
        public WifiConfiguration answer(InvocationOnMock invocation) throws Throwable {
            ScanDetail scanDetail = invocation.getArgument(0);
            return mConfig.get(scanDetail.toKeyString());
        }
    }

    private GetConfiguredNetworkForScanDetailsAnswer mGetConfiguredNetworkForScanDetailsAnswer;

    private class AddOrUpdateNetworkAnswer implements Answer<NetworkUpdateResult> {
        private Map<String, Integer> mConfigs = new HashMap<>();

        public void addConfig(WifiConfiguration config, int networkId) {
            mConfigs.put(config.configKey(), networkId);
        }

        @Override
        public NetworkUpdateResult answer(InvocationOnMock invocation) throws Throwable {
            WifiConfiguration config = invocation.getArgument(0);
            Integer networkId = mConfigs.get(config.configKey());
            if (networkId == null) return null;

            NetworkUpdateResult networkUpdateResult = mock(NetworkUpdateResult.class);
            when(networkUpdateResult.isSuccess()).thenReturn(true);
            when(networkUpdateResult.getNetworkId()).thenReturn(networkId);

            return networkUpdateResult;
        }
    }

    private AddOrUpdateNetworkAnswer mAddOrUpdateNetworkAnswer;

    private void configureNewSsid(int networkId, ScanDetail scanDetail, boolean isEphemeral,
            boolean isSaved) {
        WifiConfiguration newConfig = ScanResultUtil.createNetworkFromScanResult(
                scanDetail.getScanResult());
        newConfig.ephemeral = isEphemeral;

        if (isSaved) {
            mGetConfiguredNetworkForScanDetailsAnswer.addConfig(scanDetail, newConfig);
        }

        when(mWifiConfigManager.enableNetwork(networkId, false, Process.WIFI_UID)).thenReturn(true);
        when(mWifiConfigManager.setNetworkCandidateScanResult(eq(networkId), any(),
                anyInt())).thenReturn(true);
        when(mWifiConfigManager.getConfiguredNetwork(networkId)).thenReturn(newConfig);
        mAddOrUpdateNetworkAnswer.addConfig(newConfig, networkId);
    }

    /** Sets up test. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mDut = new CarrierNetworkEvaluator(mWifiConfigManager, mCarrierNetworkConfig, mLocalLog);

        when(mCarrierNetworkConfig.isCarrierEncryptionInfoAvailable()).thenReturn(true);

        when(mCarrierNetworkConfig.isCarrierNetwork(eq(CARRIER1_SSID))).thenReturn(true);
        when(mCarrierNetworkConfig.isCarrierNetwork(eq(CARRIER2_SSID))).thenReturn(true);
        when(mCarrierNetworkConfig.isCarrierNetwork(eq(CARRIER_SAVED_SSID))).thenReturn(true);
        when(mCarrierNetworkConfig.isCarrierNetwork(eq(CARRIER_SAVED_EPH_SSID))).thenReturn(true);
        when(mCarrierNetworkConfig.getNetworkEapType(eq(CARRIER1_SSID))).thenReturn(
                WifiEnterpriseConfig.Eap.AKA);
        when(mCarrierNetworkConfig.getNetworkEapType(eq(CARRIER2_SSID))).thenReturn(
                WifiEnterpriseConfig.Eap.AKA_PRIME);
        when(mCarrierNetworkConfig.getNetworkEapType(eq(CARRIER_SAVED_SSID))).thenReturn(
                WifiEnterpriseConfig.Eap.SIM);
        when(mCarrierNetworkConfig.getNetworkEapType(eq(CARRIER_SAVED_EPH_SSID))).thenReturn(
                WifiEnterpriseConfig.Eap.AKA);

        mAddOrUpdateNetworkAnswer = new AddOrUpdateNetworkAnswer();
        when(mWifiConfigManager.addOrUpdateNetwork(any(), eq(Process.WIFI_UID))).thenAnswer(
                mAddOrUpdateNetworkAnswer);

        mGetConfiguredNetworkForScanDetailsAnswer = new GetConfiguredNetworkForScanDetailsAnswer();
        when(mWifiConfigManager.getConfiguredNetworkForScanDetail(any())).thenAnswer(
                mGetConfiguredNetworkForScanDetailsAnswer);
    }

    /** Cleans up test. */
    @After
    public void cleanup() {
        validateMockitoUsage();
    }

    /**
     * Baseline positive test case: carrier Wi-Fi enabled (have cert), present >1 Carrier networks
     * of varying RSSI, include some none carrier networks with even better RSSI and some saved
     * carrier networks (one of which is ephemeral).
     *
     * Desired behavior:
     * - all Carrier Wi-Fi (including all saved networks) as connectable
     * - best Carrier Wi-Fi (highest RSSI) as return value
     */
    @Test
    public void testSelectOneFromMultiple() {
        String[] ssids = {CARRIER1_SSID, CARRIER2_SSID, CARRIER_SAVED_SSID, CARRIER_SAVED_EPH_SSID,
                NON_CARRIER_SSID};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4", "6c:f3:7f:ae:8c:f5",
                "6c:f3:7f:ae:8c:f6", "6c:f3:7f:ae:8c:f7"};
        int[] freqs = {2470, 2437, 2470, 2470, 2470};
        String[] caps = {"[EAP]", "[EAP]", "[EAP]", "[EAP]", "[]"};
        int[] levels = {10, 20, 11, 15, 50};

        List<ScanDetail> scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(ssids, bssids,
                freqs, caps, levels, mClock);
        configureNewSsid(CARRIER1_NET_ID, scanDetails.get(0), true, false);
        configureNewSsid(CARRIER2_NET_ID, scanDetails.get(1), true, false);
        configureNewSsid(CARRIER_SAVED_NET_ID, scanDetails.get(2), false, true);
        configureNewSsid(CARRIER_SAVED_EPH_NET_ID, scanDetails.get(3), true, false);
        configureNewSsid(NON_CARRIER_NET_ID, scanDetails.get(4), false, true);

        WifiConfiguration selected = mDut.evaluateNetworks(scanDetails, null, null, false, false,
                mConnectableListener);

        verify(mConnectableListener, times(4)).onConnectable(mScanDetailCaptor.capture(),
                mWifiConfigCaptor.capture(), anyInt());

        assertEquals(4, mScanDetailCaptor.getAllValues().size());
        assertEquals(CARRIER1_SSID, mScanDetailCaptor.getAllValues().get(0).getSSID());
        assertEquals(CARRIER2_SSID, mScanDetailCaptor.getAllValues().get(1).getSSID());
        assertEquals(CARRIER_SAVED_SSID, mScanDetailCaptor.getAllValues().get(2).getSSID());
        assertEquals(CARRIER_SAVED_EPH_SSID, mScanDetailCaptor.getAllValues().get(3).getSSID());

        assertEquals(4, mWifiConfigCaptor.getAllValues().size());
        WifiConfiguration config1 = mWifiConfigCaptor.getAllValues().get(0);
        assertEquals("\"" + CARRIER1_SSID + "\"", config1.SSID);
        assertTrue(config1.isEphemeral());
        assertTrue(config1.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP));
        WifiConfiguration config2 = mWifiConfigCaptor.getAllValues().get(1);
        assertEquals("\"" + CARRIER2_SSID + "\"", config2.SSID);
        assertTrue(config2.isEphemeral());
        assertTrue(config2.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP));
        WifiConfiguration config3 = mWifiConfigCaptor.getAllValues().get(2);
        assertEquals("\"" + CARRIER_SAVED_SSID + "\"", config3.SSID);
        assertFalse(config3.isEphemeral());
        assertTrue(config3.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP));
        WifiConfiguration config4 = mWifiConfigCaptor.getAllValues().get(3);
        assertEquals("\"" + CARRIER_SAVED_EPH_SSID + "\"", config4.SSID);
        assertTrue(config4.isEphemeral());
        assertTrue(config4.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP));

        assertEquals(config2.configKey(), selected.configKey()); // SSID2 has the highest RSSI
    }

    /**
     * Cert installed and no Carrier Wi-Fi visible
     *
     * Desired behavior: no networks connectable or selected
     */
    @Test
    public void testSelectFromNoneAvailable() {
        String[] ssids = {NON_CARRIER_SSID};
        String[] bssids = {"6c:f3:7f:ae:8c:f6"};
        int[] freqs = {2470};
        String[] caps = {"[]"};
        int[] levels = {40};

        List<ScanDetail> scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(ssids, bssids,
                freqs, caps, levels, mClock);
        configureNewSsid(NON_CARRIER_NET_ID, scanDetails.get(0), false, true);

        WifiConfiguration selected = mDut.evaluateNetworks(scanDetails, null, null, false, false,
                mConnectableListener);

        verify(mConnectableListener, never()).onConnectable(any(), any(), anyInt());
        assertNull(selected);
    }

    /**
     * Multiple carrier Wi-Fi networks visible but no cert installed.
     *
     * Desired behavior: no networks connectable or selected
     */
    @Test
    public void testNoCarrierCert() {
        String[] ssids = {CARRIER1_SSID, CARRIER2_SSID, CARRIER_SAVED_SSID, CARRIER_SAVED_EPH_SSID,
                NON_CARRIER_SSID};
        String[] bssids = {"6c:f3:7f:ae:8c:f3", "6c:f3:7f:ae:8c:f4", "6c:f3:7f:ae:8c:f5",
                "6c:f3:7f:ae:8c:f6", "6c:f3:7f:ae:8c:f7"};
        int[] freqs = {2470, 2437, 2470, 2470, 2470};
        String[] caps = {"[EAP]", "[EAP]", "[EAP]", "[EAP]", "[]"};
        int[] levels = {10, 20, 30, 40, 50};

        when(mCarrierNetworkConfig.isCarrierEncryptionInfoAvailable()).thenReturn(false);

        List<ScanDetail> scanDetails = WifiNetworkSelectorTestUtil.buildScanDetails(ssids, bssids,
                freqs, caps, levels, mClock);
        configureNewSsid(CARRIER1_NET_ID, scanDetails.get(0), true, false);
        configureNewSsid(CARRIER2_NET_ID, scanDetails.get(1), true, false);
        configureNewSsid(CARRIER_SAVED_NET_ID, scanDetails.get(2), false, true);
        configureNewSsid(CARRIER_SAVED_EPH_NET_ID, scanDetails.get(3), true, false);
        configureNewSsid(NON_CARRIER_NET_ID, scanDetails.get(4), false, true);

        WifiConfiguration selected = mDut.evaluateNetworks(scanDetails, null, null, false, false,
                mConnectableListener);

        verify(mConnectableListener, never()).onConnectable(any(), any(), anyInt());
        assertNull(selected);
    }
}
