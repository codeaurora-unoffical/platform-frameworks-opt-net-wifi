/*
 * Copyright (c) 2018, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.server.wifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.util.Log;

import com.android.internal.R;

import java.lang.Integer;
import java.util.HashSet;
import java.util.Arrays;


public class WifiVendorService {

    private static final String TAG = "WifiVendorService";

    private boolean mVerboseLoggingEnabled = false;

    private final Object mLock = new Object();

    private final HashSet<Integer> mDnbsSet = new HashSet<>();

    private int mWifiApState = WifiManager.WIFI_AP_STATE_FAILED;

    private int mWifiP2pState = WIFI_P2P_STATE_INIT;

    private boolean mDnbsSupport = false;

    // Constants
    private static final int DNBS_MAX_UID = 10;

    private static final int WIFI_P2P_STATE_INIT = 0;

    private static final int WIFI_P2P_STATE_CLIENT = 1;

    private static final int WIFI_P2P_STATE_GO = 2;

    // Reference to external Objects
    private final Context mContext;
    private final WifiInjector mWifiInjector;

    WifiVendorService(Context context, WifiInjector wifiInjector) {
        mContext = context;
        mWifiInjector = wifiInjector;
        mDnbsSupport = mContext.getResources().getBoolean(R.bool.config_wifi_dnbs_support);
        Log.d(TAG, "wifi dnbs support is " + mDnbsSupport);
        if (!mDnbsSupport) {
            Log.d(TAG, "wifi dnbs not support, ignore register broadcast receiver");
            return;
        }

        // Register for broadcast events
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        mContext.registerReceiver(
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (action.equals(
                            WifiManager.WIFI_AP_STATE_CHANGED_ACTION)) {
                        mWifiApState = intent.getIntExtra(
                                WifiManager.EXTRA_WIFI_AP_STATE,
                                WifiManager.WIFI_AP_STATE_FAILED);

                        Log.d(TAG, "WIFI_AP_STATE_CHANGED_ACTION -"
                                + " mWifiApState=" + mWifiApState);
                        if (mWifiApState ==
                                WifiManager.WIFI_AP_STATE_ENABLED) {
                            synchronized (mLock) {
                                if (mDnbsSet.size() > 0) {
                                    checkAndSetDnbsEnable(true);
                                }
                            }
                        }
                        // TODO: need to check if AP Disabled, then unset?

                    } else if (action.equals(
                            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)) {
                        NetworkInfo networkInfo =  intent.getParcelableExtra(
                                WifiP2pManager.EXTRA_NETWORK_INFO);
                        WifiP2pInfo p2pInfo = intent.getParcelableExtra(
                                WifiP2pManager.EXTRA_WIFI_P2P_INFO);

                        Log.d(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION -"
                                + " isConnected=" + networkInfo.isConnected()
                                + " groupFormed=" + p2pInfo.groupFormed
                                + " isGroupOwner=" + p2pInfo.isGroupOwner);

                        if (networkInfo.isConnected()) {
                            if (p2pInfo.groupFormed && p2pInfo.isGroupOwner) {
                                mWifiP2pState = WIFI_P2P_STATE_GO;
                                synchronized (mLock) {
                                    if (mDnbsSet.size() > 0) {
                                        checkAndSetDnbsEnable(true);
                                    }
                                }
                            } else {
                                // TODO: need to unset when GC?
                                mWifiP2pState = WIFI_P2P_STATE_CLIENT;
                            }
                        } else {
                            // TODO: need to unset when GO disconnect?
                            mWifiP2pState = WIFI_P2P_STATE_INIT;
                        }
                    }
                }
            },
            new IntentFilter(filter));
    }

    public boolean setDnbsEnabled(int uid, boolean enable) {
        if (!mDnbsSupport) {
            Log.d(TAG, "wifi dnbs not support, ignore setDbnsEnabled");
            return false;
        }
        synchronized (mLock) {
            if (enable == true && mDnbsSet.contains(uid) == false) {
               if (mDnbsSet.size() >= DNBS_MAX_UID) {
                   Log.i(TAG, "setDbnsEnable: uid=" + uid + " enable=" + enable
                         + " fail due to exceed DNBS_MAX_UID=" + DNBS_MAX_UID);
                   return false;
               }
               mDnbsSet.add(uid);
               // From none to ENABLE
               if (mDnbsSet.size() == 1) {
                   return checkAndSetDnbsEnable(true);
               }
            } else if (enable == false && mDnbsSet.contains(uid) == true) {
               mDnbsSet.remove(uid);
               // From ENABLE to none
               if (mDnbsSet.size() == 0) {
                   return checkAndSetDnbsEnable(false);
               }
            }
        }

        return true;
    }

    private boolean checkAndSetDnbsEnable(boolean enable) {
        Log.i(TAG, "checkAndSetDnbsEanble: " + enable
                + " mWifiApState=" + mWifiApState
                + " mWifiP2pState=" + mWifiP2pState);

        if (mWifiApState == WifiManager.WIFI_AP_STATE_ENABLED) {
            String ifname = mWifiInjector.getWifiApConfigStore().getSapInterface();
            mWifiInjector.getWifiVendorHal().setRestrictedOffChannel(ifname, enable);
        }

        if (mWifiP2pState == WIFI_P2P_STATE_GO) {
            mWifiInjector.getWifiVendorHal().setRestrictedOffChannel("p2p0", enable);
        }

        return true;
    }

    public String dumpDnbs() {
        StringBuffer sb = new StringBuffer();
        synchronized (mLock) {
            sb.append("DNBS feature  : ");
            if (mDnbsSupport) {
                sb.append("Enabled");
            } else {
                sb.append("Disabled");
            }
            sb.append("\n");

            sb.append("Max num of UID: ");
            sb.append(DNBS_MAX_UID);
            sb.append("\n");

            sb.append("UID set DNBS  : ");
            sb.append(Arrays.toString(mDnbsSet.toArray()));
            sb.append("\n");

            sb.append("WifiApState   : ");
            if (mWifiApState == WifiManager.WIFI_AP_STATE_ENABLED) {
                sb.append("Enabled");
            } else if (mWifiApState == WifiManager.WIFI_AP_STATE_DISABLED) {
                sb.append("Disabled");
            } else {
                sb.append(mWifiApState);
            }
            sb.append("\n");

            sb.append("WifiP2pState  : ");
            if (mWifiP2pState == WIFI_P2P_STATE_CLIENT) {
                sb.append("GC active");
            } else if (mWifiP2pState == WIFI_P2P_STATE_GO) {
                sb.append("GO active");
            } else {
                sb.append("Inactive");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
