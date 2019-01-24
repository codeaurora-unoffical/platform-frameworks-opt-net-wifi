/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.wifi.hotspot2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.ProvisioningCallback;
import android.net.wifi.hotspot2.omadm.PpsMoParser;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.net.wifi.hotspot2.pps.UpdateParameter;
import android.os.Build;
import android.os.Handler;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.support.test.filters.SmallTest;
import android.telephony.TelephonyManager;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.org.conscrypt.TrustManagerImpl;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.hotspot2.soap.ExchangeCompleteMessage;
import com.android.server.wifi.hotspot2.soap.PostDevDataResponse;
import com.android.server.wifi.hotspot2.soap.RedirectListener;
import com.android.server.wifi.hotspot2.soap.SppConstants;
import com.android.server.wifi.hotspot2.soap.SppResponseMessage;
import com.android.server.wifi.hotspot2.soap.UpdateResponseMessage;
import com.android.server.wifi.hotspot2.soap.command.BrowserUri;
import com.android.server.wifi.hotspot2.soap.command.PpsMoData;
import com.android.server.wifi.hotspot2.soap.command.SppCommand;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.net.URL;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLContext;

/**
 * Unit tests for {@link PasspointProvisioner}.
 */
@SmallTest
public class PasspointProvisionerTest {
    private static final int TEST_UID = 1500;
    private static final int STEP_INIT = 0;
    private static final int STEP_AP_CONNECT = 1;
    private static final int STEP_SERVER_CONNECT = 2;
    private static final int STEP_WAIT_FOR_REDIRECT_RESPONSE = 3;
    private static final int STEP_WAIT_FOR_SECOND_SOAP_RESPONSE = 4;
    private static final int STEP_WAIT_FOR_THIRD_SOAP_RESPONSE = 5;
    private static final int STEP_WAIT_FOR_TRUST_ROOT_CERTS = 6;

    private static final String TEST_DEV_ID = "12312341";
    private static final String TEST_MANUFACTURER = Build.MANUFACTURER;
    private static final String TEST_MODEL = Build.MODEL;
    private static final String TEST_LANGUAGE = "en";
    private static final String TEST_SESSION_ID = "123456";
    private static final String TEST_URL = "https://127.0.0.1/session_id=" + TEST_SESSION_ID;
    private static final String TEST_HW_VERSION = "Test HW 1.0";
    private static final String TEST_MAC_ADDR = "11:22:33:44:55:66";
    private static final String TEST_IMSI = "310150123456789";
    private static final String TEST_SW_VERSION = "Android Test 1.0";
    private static final String TEST_FW_VERSION = "Test FW 1.0";
    private static final String TEST_REDIRECT_URL = "http://127.0.0.1:12345/index.htm";
    private static final String OSU_APP_PACKAGE = "com.android.hotspot2";
    private static final String OSU_APP_NAME = "OsuLogin";

    private PasspointProvisioner mPasspointProvisioner;
    private TestLooper mLooper = new TestLooper();
    private Handler mHandler;
    private OsuNetworkConnection.Callbacks mOsuNetworkCallbacks;
    private PasspointProvisioner.OsuServerCallbacks mOsuServerCallbacks;
    private RedirectListener.RedirectCallback mRedirectReceivedListener;
    private ArgumentCaptor<OsuNetworkConnection.Callbacks> mOsuNetworkCallbacksCaptor =
            ArgumentCaptor.forClass(OsuNetworkConnection.Callbacks.class);
    private ArgumentCaptor<PasspointProvisioner.OsuServerCallbacks> mOsuServerCallbacksCaptor =
            ArgumentCaptor.forClass(PasspointProvisioner.OsuServerCallbacks.class);
    private ArgumentCaptor<RedirectListener.RedirectCallback>
            mOnRedirectReceivedArgumentCaptor =
            ArgumentCaptor.forClass(RedirectListener.RedirectCallback.class);
    private ArgumentCaptor<Handler> mHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
    private OsuProvider mOsuProvider;
    private TrustManagerImpl mDelegate;
    private URL mTestUrl;
    private MockitoSession mSession;

    @Mock PasspointObjectFactory mObjectFactory;
    @Mock Context mContext;
    @Mock WifiManager mWifiManager;
    @Mock IProvisioningCallback mCallback;
    @Mock OsuNetworkConnection mOsuNetworkConnection;
    @Mock OsuServerConnection mOsuServerConnection;
    @Mock Network mNetwork;
    @Mock WfaKeyStore mWfaKeyStore;
    @Mock KeyStore mKeyStore;
    @Mock SSLContext mTlsContext;
    @Mock WifiNative mWifiNative;
    @Mock PostDevDataResponse mSppResponseMessage;
    @Mock ExchangeCompleteMessage mExchangeCompleteMessage;
    @Mock SystemInfo mSystemInfo;
    @Mock TelephonyManager mTelephonyManager;
    @Mock SppCommand mSppCommand;
    @Mock BrowserUri mBrowserUri;
    @Mock PpsMoData mPpsMoData;
    @Mock RedirectListener mRedirectListener;
    @Mock PackageManager mPackageManager;
    @Mock PasspointConfiguration mPasspointConfiguration;
    @Mock X509Certificate mX509Certificate;
    @Mock SoapSerializationEnvelope mSoapSerializationEnvelope;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestUrl = new URL(TEST_REDIRECT_URL);
        mSession = ExtendedMockito.mockitoSession().mockStatic(
                RedirectListener.class).mockStatic(PpsMoParser.class).mockStatic(
                UpdateResponseMessage.class).startMocking();

        when(RedirectListener.createInstance(mLooper.getLooper())).thenReturn(
                mRedirectListener);
        when(mRedirectListener.getServerUrl()).thenReturn(new URL(TEST_REDIRECT_URL));
        when(mRedirectListener.startServer(
                any(RedirectListener.RedirectCallback.class))).thenReturn(true);
        when(mRedirectListener.isAlive()).thenReturn(true);
        when(mWifiManager.isWifiEnabled()).thenReturn(true);
        when(mObjectFactory.makeOsuNetworkConnection(any(Context.class)))
                .thenReturn(mOsuNetworkConnection);
        when(mObjectFactory.makeOsuServerConnection()).thenReturn(mOsuServerConnection);
        when(mWfaKeyStore.get()).thenReturn(mKeyStore);
        when(mObjectFactory.makeWfaKeyStore()).thenReturn(mWfaKeyStore);
        when(mObjectFactory.getSSLContext(any(String.class))).thenReturn(mTlsContext);
        when(mObjectFactory.getTrustManagerImpl(any(KeyStore.class))).thenReturn(mDelegate);
        when(mObjectFactory.getSystemInfo(any(Context.class), any(WifiNative.class))).thenReturn(
                mSystemInfo);
        doReturn(mWifiManager).when(mContext)
                .getSystemService(eq(Context.WIFI_SERVICE));
        mPasspointProvisioner = new PasspointProvisioner(mContext, mWifiNative, mObjectFactory);
        when(mOsuNetworkConnection.connect(any(WifiSsid.class), any())).thenReturn(true);
        when(mOsuServerConnection.connect(any(URL.class), any(Network.class))).thenReturn(true);
        when(mOsuServerConnection.validateProvider(any(Locale.class),
                any(String.class))).thenReturn(true);
        when(mOsuServerConnection.canValidateServer()).thenReturn(true);
        mPasspointProvisioner.enableVerboseLogging(1);
        mOsuProvider = PasspointProvisioningTestUtil.generateOsuProvider(true);
        mDelegate = new TrustManagerImpl(PasspointProvisioningTestUtil.createFakeKeyStore());
        when(mObjectFactory.getTrustManagerImpl(any(KeyStore.class))).thenReturn(mDelegate);
        when(mContext.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(mTelephonyManager);
        when(mSystemInfo.getDeviceModel()).thenReturn(TEST_MODEL);
        when(mSystemInfo.getLanguage()).thenReturn(TEST_LANGUAGE);
        when(mSystemInfo.getDeviceId()).thenReturn(TEST_DEV_ID);
        when(mSystemInfo.getDeviceManufacturer()).thenReturn(TEST_MANUFACTURER);
        when(mSystemInfo.getHwVersion()).thenReturn(TEST_HW_VERSION);
        when(mSystemInfo.getMacAddress(any(String.class))).thenReturn(TEST_MAC_ADDR);
        when(mSystemInfo.getSoftwareVersion()).thenReturn(TEST_SW_VERSION);
        when(mSystemInfo.getFirmwareVersion()).thenReturn(TEST_FW_VERSION);
        when(mTelephonyManager.getSubscriberId()).thenReturn(TEST_IMSI);

        when(mExchangeCompleteMessage.getMessageType()).thenReturn(
                SppResponseMessage.MessageType.EXCHANGE_COMPLETE);
        when(mExchangeCompleteMessage.getStatus()).thenReturn(
                SppConstants.SppStatus.EXCHANGE_COMPLETE);
        when(mExchangeCompleteMessage.getSessionID()).thenReturn(TEST_SESSION_ID);
        when(mExchangeCompleteMessage.getError()).thenReturn(SppConstants.INVALID_SPP_CONSTANT);
        when(mSppResponseMessage.getMessageType()).thenReturn(
                SppResponseMessage.MessageType.POST_DEV_DATA_RESPONSE);
        when(mSppResponseMessage.getSppCommand()).thenReturn(mSppCommand);
        when(mSppResponseMessage.getSessionID()).thenReturn(TEST_SESSION_ID);
        when(mSppCommand.getSppCommandId()).thenReturn(SppCommand.CommandId.EXEC);
        when(mSppCommand.getExecCommandId()).thenReturn(SppCommand.ExecCommandId.BROWSER);
        when(mSppCommand.getCommandData()).thenReturn(mBrowserUri);
        when(mBrowserUri.getUri()).thenReturn(TEST_URL);
        when(mOsuServerConnection.exchangeSoapMessage(
                any(SoapSerializationEnvelope.class))).thenReturn(true);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.activityInfo = new ActivityInfo();
        resolveInfo.activityInfo.applicationInfo = new ApplicationInfo();
        resolveInfo.activityInfo.name = OSU_APP_NAME;
        resolveInfo.activityInfo.applicationInfo.packageName = OSU_APP_PACKAGE;
        when(mPackageManager.resolveActivity(any(Intent.class),
                eq(PackageManager.MATCH_DEFAULT_ONLY))).thenReturn(resolveInfo);

        Map<String, byte[]> trustCertInfo = new HashMap<>();
        trustCertInfo.put("https://testurl.com", "testData".getBytes());
        when(mPasspointConfiguration.getTrustRootCertList()).thenReturn(trustCertInfo);
        when(mPasspointConfiguration.getCredential()).thenReturn(new Credential());
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn("test.com");
        when(mPasspointConfiguration.getHomeSp()).thenReturn(homeSp);

        UpdateParameter updateParameter = new UpdateParameter();
        updateParameter.setTrustRootCertUrl("https://testurl.com");
        updateParameter.setTrustRootCertSha256Fingerprint("testData".getBytes());
        when(mPasspointConfiguration.getSubscriptionUpdate()).thenReturn(updateParameter);
        when(mOsuServerConnection.retrieveTrustRootCerts(anyMap())).thenReturn(true);
        lenient().when(PpsMoParser.parseMoText(isNull())).thenReturn(mPasspointConfiguration);

        when(mPasspointConfiguration.validateForR2()).thenReturn(true);
        lenient().when(UpdateResponseMessage.serializeToSoapEnvelope(any(String.class),
                any(Boolean.class))).thenReturn(mSoapSerializationEnvelope);
    }

    @After
    public void cleanUp() {
        mSession.finishMocking();
    }

    private void initAndStartProvisioning() {
        mPasspointProvisioner.init(mLooper.getLooper());
        verify(mOsuNetworkConnection).init(mHandlerCaptor.capture());

        mHandler = mHandlerCaptor.getValue();
        assertEquals(mHandler.getLooper(), mLooper.getLooper());

        mLooper.dispatchAll();

        assertTrue(mPasspointProvisioner.startSubscriptionProvisioning(
                TEST_UID, mOsuProvider, mCallback));
        // Runnable posted by the provisioning start request
        assertEquals(mHandler.hasMessagesOrCallbacks(), true);
        mLooper.dispatchAll();
        assertEquals(mHandler.hasMessagesOrCallbacks(), false);

        verify(mOsuNetworkConnection, atLeastOnce())
                .setEventCallback(mOsuNetworkCallbacksCaptor.capture());
        mOsuNetworkCallbacks = mOsuNetworkCallbacksCaptor.getAllValues().get(0);
        verify(mOsuServerConnection, atLeastOnce())
                .setEventCallback(mOsuServerCallbacksCaptor.capture());
        mOsuServerCallbacks = mOsuServerCallbacksCaptor.getAllValues().get(0);
    }

    private void stopAfterStep(int finalStep) throws RemoteException {
        for (int step = STEP_INIT; step <= finalStep; step++) {
            if (step == STEP_INIT) {
                initAndStartProvisioning();
                verify(mCallback).onProvisioningStatus(
                        ProvisioningCallback.OSU_STATUS_AP_CONNECTING);
            } else if (step == STEP_AP_CONNECT) {
                // Connection to OSU AP successful
                mOsuNetworkCallbacks.onConnected(mNetwork);

                verify(mCallback).onProvisioningStatus(
                        ProvisioningCallback.OSU_STATUS_AP_CONNECTED);
            } else if (step == STEP_SERVER_CONNECT) {
                verify(mCallback).onProvisioningStatus(
                        ProvisioningCallback.OSU_STATUS_SERVER_CONNECTING);
            } else if (step == STEP_WAIT_FOR_REDIRECT_RESPONSE) {
                // Server validation passed
                mOsuServerCallbacks.onServerValidationStatus(mOsuServerCallbacks.getSessionId(),
                        true);
                mLooper.dispatchAll();

                verify(mCallback).onProvisioningStatus(
                        ProvisioningCallback.OSU_STATUS_SERVER_VALIDATED);

                // Server connection succeeded
                mOsuServerCallbacks.onServerConnectionStatus(mOsuServerCallbacks.getSessionId(),
                        true);
                mLooper.dispatchAll();

                verify(mCallback).onProvisioningStatus(
                        ProvisioningCallback.OSU_STATUS_SERVER_CONNECTED);
                verify(mCallback).onProvisioningStatus(
                        ProvisioningCallback.OSU_STATUS_INIT_SOAP_EXCHANGE);

                // Received a first soapMessageResponse
                mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(),
                        mSppResponseMessage);
                mLooper.dispatchAll();

                verify(mCallback).onProvisioningStatus(
                        ProvisioningCallback.OSU_STATUS_WAITING_FOR_REDIRECT_RESPONSE);
                verify(mRedirectListener, atLeastOnce())
                        .startServer(mOnRedirectReceivedArgumentCaptor.capture());
                mRedirectReceivedListener = mOnRedirectReceivedArgumentCaptor.getValue();
                verifyNoMoreInteractions(mCallback);
            } else if (step == STEP_WAIT_FOR_SECOND_SOAP_RESPONSE) {
                when(mSppCommand.getSppCommandId()).thenReturn(SppCommand.CommandId.ADD_MO);
                when(mSppCommand.getExecCommandId()).thenReturn(-1);
                when(mSppCommand.getCommandData()).thenReturn(mPpsMoData);

                // Received HTTP redirect response.
                mRedirectReceivedListener.onRedirectReceived();
                mLooper.dispatchAll();

                verify(mRedirectListener, atLeastOnce()).stopServer();
                verify(mCallback).onProvisioningStatus(
                        ProvisioningCallback.OSU_STATUS_REDIRECT_RESPONSE_RECEIVED);
                verify(mCallback).onProvisioningStatus(
                        ProvisioningCallback.OSU_STATUS_SECOND_SOAP_EXCHANGE);

                // Received a second soapMessageResponse
                mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(),
                        mSppResponseMessage);
                mLooper.dispatchAll();
            } else if (step == STEP_WAIT_FOR_THIRD_SOAP_RESPONSE) {
                ExtendedMockito.verify(
                        () -> UpdateResponseMessage.serializeToSoapEnvelope(eq(TEST_SESSION_ID),
                                eq(false)));
                verify(mCallback).onProvisioningStatus(
                        ProvisioningCallback.OSU_STATUS_THIRD_SOAP_EXCHANGE);

                // Received a third soapMessageResponse
                mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(),
                        mExchangeCompleteMessage);
                mLooper.dispatchAll();
            } else if (step == STEP_WAIT_FOR_TRUST_ROOT_CERTS) {
                verify(mCallback).onProvisioningStatus(
                        ProvisioningCallback.OSU_STATUS_RETRIEVING_TRUST_ROOT_CERTS);

                Map<Integer, List<X509Certificate>> trustRootCertificates = new HashMap<>();
                List<X509Certificate> certificates = new ArrayList<>();
                certificates.add(mX509Certificate);
                trustRootCertificates.put(OsuServerConnection.TRUST_CERT_TYPE_AAA, certificates);

                // Received trust root CA certificates
                mOsuServerCallbacks.onReceivedTrustRootCertificates(
                        mOsuServerCallbacks.getSessionId(), trustRootCertificates);
                mLooper.dispatchAll();
                verify(mCallback).onProvisioningComplete();
            }
        }
    }

    /**
     * Verifies initialization and starting subscription provisioning flow
     */
    @Test
    public void verifyInitAndStartProvisioning() {
        initAndStartProvisioning();
    }

    /**
     * Verifies initialization and starting subscription provisioning flow
     */
    @Test
    public void verifyProvisioningUnavailable() throws RemoteException {
        when(mOsuServerConnection.canValidateServer()).thenReturn(false);
        initAndStartProvisioning();
        verify(mCallback).onProvisioningFailure(
                ProvisioningCallback.OSU_FAILURE_PROVISIONING_NOT_AVAILABLE);
    }

    /**
     * Verifies existing provisioning flow is aborted before starting another one.
     */
    @Test
    public void verifyProvisioningAbort() throws RemoteException {
        initAndStartProvisioning();

        IProvisioningCallback mCallback2 = mock(IProvisioningCallback.class);
        assertTrue(mPasspointProvisioner.startSubscriptionProvisioning(
                TEST_UID, mOsuProvider, mCallback2));
        mLooper.dispatchAll();
        verify(mCallback).onProvisioningFailure(
                ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
    }

    /**
     * Verifies that if connection attempt to OSU AP fails, corresponding error callback is invoked.
     */
    @Test
    public void verifyConnectAttemptFailure() throws RemoteException {
        when(mOsuNetworkConnection.connect(any(WifiSsid.class), any())).thenReturn(false);
        initAndStartProvisioning();

        // Since connection attempt fails, directly move to FAILED_STATE
        verify(mCallback).onProvisioningFailure(ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
        // Failure case, no more runnables posted
        verifyNoMoreInteractions(mCallback);
    }

    /**
     * Verifies that after connection attempt succeeds and a network disconnection occurs, the
     * corresponding failure callback is invoked.
     */
    @Test
    public void verifyConnectAttemptedAndConnectionFailed() throws RemoteException {
        stopAfterStep(STEP_INIT);

        // state expected is WAITING_TO_CONNECT
        verify(mCallback).onProvisioningStatus(ProvisioningCallback.OSU_STATUS_AP_CONNECTING);
        // Connection to OSU AP fails
        mOsuNetworkCallbacks.onDisconnected();

        // Move to failed state
        verify(mCallback).onProvisioningFailure(ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
        // Failure case, no more runnable posted
        verifyNoMoreInteractions(mCallback);
    }

    /**
     * Verifies that a connection drop is reported via callback.
     */
    @Test
    public void verifyConnectionDrop() throws RemoteException {
        stopAfterStep(STEP_SERVER_CONNECT);

        // Disconnect received
        mOsuNetworkCallbacks.onDisconnected();

        // Move to failed state
        verify(mCallback).onProvisioningFailure(ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
        // No more callbacks, Osu server validation not initiated
        verifyNoMoreInteractions(mCallback);
    }

    /**
     * Verifies that Wifi Disable while provisioning is communicated as provisioning failure
     */
    @Test
    public void verifyFailureDueToWifiDisable() throws RemoteException {
        stopAfterStep(STEP_SERVER_CONNECT);

        // Wifi disabled notification
        mOsuNetworkCallbacks.onWifiDisabled();

        // Wifi Disable is processed first and move to failed state
        verify(mCallback).onProvisioningFailure(ProvisioningCallback.OSU_FAILURE_AP_CONNECTION);
        // OSU server connection event is not handled
        verifyNoMoreInteractions(mCallback);
    }

   /**
     * Verifies that the right provisioning callbacks are invoked as the provisioner connects
     * to OSU AP and OSU server and that invalid server URL generates the right error callback.
     */
    @Test
    public void verifyInvalidOsuServerURL() throws RemoteException {
        mOsuProvider = PasspointProvisioningTestUtil.generateInvalidServerUrlOsuProvider();
        initAndStartProvisioning();

        // Attempting to connect to OSU server fails due to invalid server URL, move to failed state
        verify(mCallback).onProvisioningFailure(
                ProvisioningCallback.OSU_FAILURE_SERVER_URL_INVALID);
        // No further runnable posted
        verifyNoMoreInteractions(mCallback);
    }

    /**
     * Verifies that the right provisioning callbacks are invoked as the provisioner progresses
     * to associating with the OSU AP and connection to OSU server fails.
     */
    @Test
    public void verifyServerConnectionFailure() throws RemoteException {
        when(mOsuServerConnection.connect(any(URL.class), any(Network.class))).thenReturn(false);
        stopAfterStep(STEP_AP_CONNECT);

        // Connection to OSU Server fails, move to failed state
        verify(mCallback).onProvisioningFailure(ProvisioningCallback.OSU_FAILURE_SERVER_CONNECTION);
        // No further runnable posted
        verifyNoMoreInteractions(mCallback);
    }

    /**
     * Verifies that the right provisioning callbacks are invoked as the provisioner is unable
     * to validate the OSU Server
     */
    @Test
    public void verifyServerValidationFailure() throws RemoteException {
        stopAfterStep(STEP_SERVER_CONNECT);

        // OSU Server validation of certs fails
        mOsuServerCallbacks.onServerValidationStatus(mOsuServerCallbacks.getSessionId(), false);
        mLooper.dispatchAll();

        // Server validation failure, move to failed state
        verify(mCallback).onProvisioningFailure(ProvisioningCallback.OSU_FAILURE_SERVER_VALIDATION);
        // No further runnable posted
        verifyNoMoreInteractions(mCallback);
    }

    /**
     * Verifies that the status for server validation from a previous session is ignored
     * by the provisioning state machine
     */
    @Test
    public void verifyServerValidationFailurePreviousSession() throws RemoteException {
        stopAfterStep(STEP_SERVER_CONNECT);

        // OSU Server validation of certs failure but from a previous session
        mOsuServerCallbacks.onServerValidationStatus(mOsuServerCallbacks.getSessionId() - 1, false);
        // Runnable posted by server callback
        assertTrue(mHandler.hasMessagesOrCallbacks());
        mLooper.dispatchAll();

        // Server validation failure, move to failed state
        verify(mCallback, never()).onProvisioningFailure(
                ProvisioningCallback.OSU_FAILURE_SERVER_VALIDATION);
        // No further runnable posted
        verifyNoMoreInteractions(mCallback);
    }

    /**
     * Verifies that the status for server validation from a previous session is ignored
     * by the provisioning state machine
     */
    @Test
    public void verifyServerValidationSuccessPreviousSession() throws RemoteException {
        stopAfterStep(STEP_SERVER_CONNECT);

        // OSU Server validation success but from a previous session
        mOsuServerCallbacks.onServerValidationStatus(mOsuServerCallbacks.getSessionId() - 1, true);
        // Runnable posted by server callback
        assertTrue(mHandler.hasMessagesOrCallbacks());
        mLooper.dispatchAll();

        // Ignore the validation complete event because of different session id.
        verify(mCallback, never()).onProvisioningStatus(
                ProvisioningCallback.OSU_STATUS_SERVER_VALIDATED);
        // No further runnable posted
        verifyNoMoreInteractions(mCallback);
    }

    /**
     * Verifies that the right provisioning callbacks are invoked when provisioner is unable
     * to validate the OSU provider
     */
    @Test
    public void verifyProviderVerificationFailure() throws RemoteException {
        when(mOsuServerConnection.validateProvider(any(Locale.class),
                any(String.class))).thenReturn(false);
        stopAfterStep(STEP_SERVER_CONNECT);

        // Wait for OSU server validation callback
        mOsuServerCallbacks.onServerValidationStatus(mOsuServerCallbacks.getSessionId(), true);
        // Runnable posted by server callback
        assertTrue(mHandler.hasMessagesOrCallbacks());
        // OSU server validation success posts another runnable to validate the provider
        mLooper.dispatchAll();

        // Provider validation failure is processed next, move to failed state
        verify(mCallback).onProvisioningFailure(
                ProvisioningCallback.OSU_FAILURE_SERVICE_PROVIDER_VERIFICATION);
        // No further runnable posted
        verifyNoMoreInteractions(mCallback);
    }

    /**
     * Verifies that the right provisioning callbacks are invoked when SOAP message exchange fails.
     */
    @Test
    public void verifyExchangingSoapMessageFailure() throws RemoteException {
        // Fail to exchange the SOAP message
        when(mOsuServerConnection.exchangeSoapMessage(
                any(SoapSerializationEnvelope.class))).thenReturn(false);
        stopAfterStep(STEP_SERVER_CONNECT);

        // Server validation passed
        mOsuServerCallbacks.onServerValidationStatus(mOsuServerCallbacks.getSessionId(), true);
        mLooper.dispatchAll();

        verify(mCallback).onProvisioningStatus(ProvisioningCallback.OSU_STATUS_SERVER_VALIDATED);

        // Server connection passed
        mOsuServerCallbacks.onServerConnectionStatus(mOsuServerCallbacks.getSessionId(), true);
        mLooper.dispatchAll();

        verify(mCallback).onProvisioningStatus(
                ProvisioningCallback.OSU_STATUS_SERVER_CONNECTED);
        verify(mCallback).onProvisioningFailure(
                ProvisioningCallback.OSU_FAILURE_SOAP_MESSAGE_EXCHANGE);
        // No further runnable posted
        verifyNoMoreInteractions(mCallback);
    }

    /**
     * Verifies that the right provisioning callbacks are invoked when there is no OSU activity for
     * the intent
     */
    @Test
    public void verifyNoOsuActivityFoundFailure() throws RemoteException {
        // There is no activity found for the intent
        when(mPackageManager.resolveActivity(any(Intent.class),
                eq(PackageManager.MATCH_DEFAULT_ONLY))).thenReturn(null);
        stopAfterStep(STEP_SERVER_CONNECT);

        // Server validation passed
        mOsuServerCallbacks.onServerValidationStatus(mOsuServerCallbacks.getSessionId(), true);
        mLooper.dispatchAll();

        verify(mCallback).onProvisioningStatus(ProvisioningCallback.OSU_STATUS_SERVER_VALIDATED);

        // Server connection passed
        mOsuServerCallbacks.onServerConnectionStatus(mOsuServerCallbacks.getSessionId(), true);
        mLooper.dispatchAll();

        verify(mCallback).onProvisioningStatus(
                ProvisioningCallback.OSU_STATUS_SERVER_CONNECTED);
        verify(mCallback).onProvisioningStatus(ProvisioningCallback.OSU_STATUS_INIT_SOAP_EXCHANGE);

        // Received soapMessageResponse
        mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(),
                mSppResponseMessage);
        mLooper.dispatchAll();

        verify(mCallback).onProvisioningFailure(
                ProvisioningCallback.OSU_FAILURE_NO_OSU_ACTIVITY_FOUND);
        // No further runnable posted
        verifyNoMoreInteractions(mCallback);
    }

    /**
     * Verifies that the right provisioning callbacks are invoked when timeout occurs for HTTP
     * redirect response.
     */
    @Test
    public void verifyRedirectResponseTimeout() throws RemoteException {
        stopAfterStep(STEP_WAIT_FOR_REDIRECT_RESPONSE);

        // Timed out for HTTP redirect response.
        mRedirectReceivedListener.onRedirectTimedOut();
        mLooper.dispatchAll();

        verify(mRedirectListener, atLeastOnce()).stopServer();
        verify(mCallback).onProvisioningFailure(
                ProvisioningCallback.OSU_FAILURE_TIMED_OUT_REDIRECT_LISTENER);
        // No further runnable posted
        verifyNoMoreInteractions(mCallback);
    }

    /**
     * Verifies that the right provisioning callbacks are invoked when a command of a second soap
     * response {@link PostDevDataResponse} is not for ADD MO command.
     */
    @Test
    public void verifyNotAddMoCommandFailureForSecondSoapResponse() throws RemoteException {
        stopAfterStep(STEP_WAIT_FOR_REDIRECT_RESPONSE);

        // Received HTTP redirect response.
        mRedirectReceivedListener.onRedirectReceived();
        mLooper.dispatchAll();

        verify(mRedirectListener, atLeastOnce()).stopServer();
        verify(mCallback).onProvisioningStatus(
                ProvisioningCallback.OSU_STATUS_REDIRECT_RESPONSE_RECEIVED);
        verify(mCallback).onProvisioningStatus(
                ProvisioningCallback.OSU_STATUS_SECOND_SOAP_EXCHANGE);

        // Received a second soapMessageResponse
        mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(),
                mSppResponseMessage);
        mLooper.dispatchAll();

        verify(mCallback).onProvisioningFailure(
                ProvisioningCallback.OSU_FAILURE_UNEXPECTED_COMMAND_TYPE);
    }

    /**
     * Verifies that the right provisioning callbacks are invoked when a message of a third soap
     * response {@link ExchangeCompleteMessage} has an error property.
     */
    @Test
    public void verifyHandlingErrorPropertyInThirdSoapResponse() throws RemoteException {
        when(mExchangeCompleteMessage.getError()).thenReturn(
                SppConstants.SppError.PROVISIONING_FAILED);
        stopAfterStep(STEP_WAIT_FOR_SECOND_SOAP_RESPONSE);

        verify(mCallback).onProvisioningStatus(
                ProvisioningCallback.OSU_STATUS_THIRD_SOAP_EXCHANGE);

        // Received a third soapMessageResponse
        mOsuServerCallbacks.onReceivedSoapMessage(mOsuServerCallbacks.getSessionId(),
                mExchangeCompleteMessage);
        mLooper.dispatchAll();

        verify(mCallback).onProvisioningFailure(
                ProvisioningCallback.OSU_FAILURE_PROVISIONING_ABORTED);
    }

    /**
     * Verifies that the {@link UpdateResponseMessage#serializeToSoapEnvelope(String, boolean)} is
     * invoked with right arguments when a PPS-MO xml received from server is not valid in terms of
     * schema of PPS MO.
     */
    @Test
    public void verifyHandlingInvalidPpsMoForSoapResponse() throws RemoteException {
        when(mPasspointConfiguration.validateForR2()).thenReturn(false);
        stopAfterStep(STEP_WAIT_FOR_SECOND_SOAP_RESPONSE);

        ExtendedMockito.verify(
                () -> UpdateResponseMessage.serializeToSoapEnvelope(eq(TEST_SESSION_ID), eq(true)));
    }

    /**
     * Verifies that the right provisioning callbacks are invoked when failing to call {@link
     * OsuServerConnection#retrieveTrustRootCerts(Map)}.
     */
    @Test
    public void verifyHandlingErrorForCallingRetrieveTrustRootCerts()
            throws RemoteException {
        when(mOsuServerConnection.retrieveTrustRootCerts(anyMap())).thenReturn(false);
        stopAfterStep(STEP_WAIT_FOR_THIRD_SOAP_RESPONSE);

        verify(mCallback).onProvisioningFailure(
                ProvisioningCallback.OSU_FAILURE_SERVER_CONNECTION);
    }

    /**
     * Verifies that the right provisioning callbacks are invoked when a new {@link
     * PasspointConfiguration} is failed to add.
     */
    @Test
    public void verifyHandlingErrorForAddingPasspointConfiguration() throws RemoteException {
        doThrow(IllegalArgumentException.class).when(
                mWifiManager).addOrUpdatePasspointConfiguration(any(PasspointConfiguration.class));
        stopAfterStep(STEP_WAIT_FOR_THIRD_SOAP_RESPONSE);
        verify(mCallback).onProvisioningStatus(
                ProvisioningCallback.OSU_STATUS_RETRIEVING_TRUST_ROOT_CERTS);

        Map<Integer, List<X509Certificate>> trustRootCertificates = new HashMap<>();
        List<X509Certificate> certificates = new ArrayList<>();
        certificates.add(mX509Certificate);
        trustRootCertificates.put(OsuServerConnection.TRUST_CERT_TYPE_AAA, certificates);

        // Received trust root CA certificates
        mOsuServerCallbacks.onReceivedTrustRootCertificates(
                mOsuServerCallbacks.getSessionId(), trustRootCertificates);
        mLooper.dispatchAll();

        verify(mCallback).onProvisioningFailure(
                ProvisioningCallback.OSU_FAILURE_ADD_PASSPOINT_CONFIGURATION);
    }

    /**
     * Verifies that the right provisioning callbacks are invoked when it is failed to retrieve
     * trust root certificates from the URLs provided.
     */
    @Test
    public void verifyHandlingEmptyTrustRootCertificateRetrieved() throws RemoteException {
        doThrow(IllegalArgumentException.class).when(
                mWifiManager).addOrUpdatePasspointConfiguration(any(PasspointConfiguration.class));
        stopAfterStep(STEP_WAIT_FOR_THIRD_SOAP_RESPONSE);
        verify(mCallback).onProvisioningStatus(
                ProvisioningCallback.OSU_STATUS_RETRIEVING_TRUST_ROOT_CERTS);

        // Empty trust root certificates.
        Map<Integer, List<X509Certificate>> trustRootCertificates = new HashMap<>();

        // Received trust root CA certificates
        mOsuServerCallbacks.onReceivedTrustRootCertificates(
                mOsuServerCallbacks.getSessionId(), trustRootCertificates);
        mLooper.dispatchAll();

        verify(mCallback).onProvisioningFailure(
                ProvisioningCallback.OSU_FAILURE_RETRIEVE_TRUST_ROOT_CERTIFICATES);
    }

    /**
     * Verifies that the right provisioning callbacks are invoked as the provisioner progresses
     * to the end as successful case.
     */
    @Test
    public void verifyProvisioningFlowForSuccessfulCase() throws RemoteException {
        stopAfterStep(STEP_WAIT_FOR_TRUST_ROOT_CERTS);

        // No further runnable posted
        verifyNoMoreInteractions(mCallback);
    }
}
