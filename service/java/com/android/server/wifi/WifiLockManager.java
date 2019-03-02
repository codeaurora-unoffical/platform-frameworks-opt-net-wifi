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

import android.app.ActivityManager;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.WorkSource;
import android.os.WorkSource.WorkChain;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.app.IBatteryStats;
import com.android.internal.util.AsyncChannel;
import com.android.server.wifi.util.WifiAsyncChannel;
import com.android.server.wifi.util.WifiHandler;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * WifiLockManager maintains the list of wake locks held by different applications.
 */
public class WifiLockManager {
    private static final String TAG = "WifiLockManager";

    private static final int LOW_LATENCY_SUPPORT_UNDEFINED = -1;
    private static final int LOW_LATENCY_NOT_SUPPORTED     =  0;
    private static final int LOW_LATENCY_SUPPORTED         =  1;

    private int mLatencyModeSupport = LOW_LATENCY_SUPPORT_UNDEFINED;

    private boolean mVerboseLoggingEnabled = false;

    private final Context mContext;
    private final IBatteryStats mBatteryStats;
    private final FrameworkFacade mFrameworkFacade;
    private final ClientModeImpl mClientModeImpl;
    private final ActivityManager mActivityManager;
    private final ClientModeImplInterfaceHandler mCmiIfaceHandler;
    private WifiAsyncChannel mClientModeImplChannel;

    private final List<WifiLock> mWifiLocks = new ArrayList<>();
    // map UIDs to their corresponding records (for low-latency locks)
    private final SparseArray<UidRec> mLowLatencyUidWatchList = new SparseArray<>();
    private int mCurrentOpMode;
    private boolean mScreenOn = false;

    // For shell command support
    private boolean mForceHiPerfMode = false;
    private boolean mForceLowLatencyMode = false;

    // some wifi lock statistics
    private int mFullHighPerfLocksAcquired;
    private int mFullHighPerfLocksReleased;
    private int mFullLowLatencyLocksAcquired;
    private int mFullLowLatencyLocksReleased;

    WifiLockManager(Context context, IBatteryStats batteryStats,
            ClientModeImpl clientModeImpl, FrameworkFacade frameworkFacade, Looper looper) {
        mContext = context;
        mBatteryStats = batteryStats;
        mClientModeImpl = clientModeImpl;
        mFrameworkFacade = frameworkFacade;
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mCmiIfaceHandler = new ClientModeImplInterfaceHandler(looper);
        mCurrentOpMode = WifiManager.WIFI_MODE_NO_LOCKS_HELD;

        // Register for UID fg/bg transitions
        registerUidImportanceTransitions();
    }

    // Detect UIDs going foreground/background
    private void registerUidImportanceTransitions() {
        mActivityManager.addOnUidImportanceListener(new ActivityManager.OnUidImportanceListener() {
            @Override
            public void onUidImportance(final int uid, final int importance) {
                mCmiIfaceHandler.post(() -> {
                    UidRec uidRec = mLowLatencyUidWatchList.get(uid);
                    if (uidRec == null) {
                        // Not a uid in the watch list
                        return;
                    }

                    boolean newModeIsFg = (importance
                            == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND);
                    if (uidRec.mIsFg == newModeIsFg) {
                        return; // already at correct state
                    }

                    uidRec.mIsFg = newModeIsFg;
                    updateOpMode();
                });
            }
        }, ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE);
    }

    /**
     * Method allowing a calling app to acquire a Wifi WakeLock in the supplied mode.
     *
     * This method verifies that the caller has permission to make the call and that the lock mode
     * is a valid WifiLock mode.
     * @param lockMode int representation of the Wifi WakeLock type.
     * @param tag String passed to WifiManager.WifiLock
     * @param binder IBinder for the calling app
     * @param ws WorkSource of the calling app
     *
     * @return true if the lock was successfully acquired, false if the lockMode was invalid.
     */
    public boolean acquireWifiLock(int lockMode, String tag, IBinder binder, WorkSource ws) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        if (!isValidLockMode(lockMode)) {
            throw new IllegalArgumentException("lockMode =" + lockMode);
        }
        if (ws == null || ws.isEmpty()) {
            ws = new WorkSource(Binder.getCallingUid());
        } else {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.UPDATE_DEVICE_STATS, null);
        }
        return addLock(new WifiLock(lockMode, tag, binder, ws));
    }

    /**
     * Method used by applications to release a WiFi Wake lock.  This method checks permissions for
     * the caller and if allowed, releases the underlying WifiLock(s).
     *
     * @param binder IBinder for the calling app.
     * @return true if the lock was released, false if the caller did not hold any locks
     */
    public boolean releaseWifiLock(IBinder binder) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        return releaseLock(binder);
    }

    /**
     * Method used to get the strongest lock type currently held by the WifiLockManager.
     *
     * If no locks are held, WifiManager.WIFI_MODE_NO_LOCKS_HELD is returned.
     *
     * @return int representing the currently held (highest power consumption) lock.
     */
    public synchronized int getStrongestLockMode() {
        // First check if mode is forced to hi-perf
        if (mForceHiPerfMode) {
            return WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        }

        // Check if mode is forced to low-latency
        if (mForceLowLatencyMode) {
            return WifiManager.WIFI_MODE_FULL_LOW_LATENCY;
        }

        if (mScreenOn && countFgLowLatencyUids() > 0) {
            return WifiManager.WIFI_MODE_FULL_LOW_LATENCY;
        }

        if (mFullHighPerfLocksAcquired > mFullHighPerfLocksReleased) {
            return WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        }

        return WifiManager.WIFI_MODE_NO_LOCKS_HELD;
    }

    /**
     * Method to create a WorkSource containing all active WifiLock WorkSources.
     */
    public synchronized WorkSource createMergedWorkSource() {
        WorkSource mergedWS = new WorkSource();
        for (WifiLock lock : mWifiLocks) {
            mergedWS.add(lock.getWorkSource());
        }
        return mergedWS;
    }

    /**
     * Method used to update WifiLocks with a new WorkSouce.
     *
     * @param binder IBinder for the calling application.
     * @param ws WorkSource to add to the existing WifiLock(s).
     */
    public synchronized void updateWifiLockWorkSource(IBinder binder, WorkSource ws) {
        // Does the caller have permission to make this call?
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.UPDATE_DEVICE_STATS, null);

        // Now check if there is an active lock
        WifiLock wl = findLockByBinder(binder);
        if (wl == null) {
            throw new IllegalArgumentException("Wifi lock not active");
        }

        WorkSource newWorkSource;
        if (ws == null || ws.isEmpty()) {
            newWorkSource = new WorkSource(Binder.getCallingUid());
        } else {
            // Make a copy of the WorkSource before adding it to the WakeLock
            newWorkSource = new WorkSource(ws);
        }

        if (mVerboseLoggingEnabled) {
            Slog.d(TAG, "updateWifiLockWakeSource: " + wl + ", newWorkSource=" + newWorkSource);
        }

        long ident = Binder.clearCallingIdentity();
        try {
            // Log the acquire before the release to avoid "holes" in the collected data due to
            // an acquire event immediately after a release in the case where newWorkSource and
            // wl.mWorkSource share one or more attribution UIDs. BatteryStats can correctly match
            // "nested" acquire / release pairs.
            mBatteryStats.noteFullWifiLockAcquiredFromSource(newWorkSource);
            mBatteryStats.noteFullWifiLockReleasedFromSource(wl.mWorkSource);
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        if (wl.mMode == WifiManager.WIFI_MODE_FULL_LOW_LATENCY) {
            addWsToLlWatchList(newWorkSource);
            removeWsFromLlWatchList(wl.mWorkSource);
            updateOpMode();
        }

        wl.mWorkSource = newWorkSource;
    }

    /**
     * Method Used for shell command support
     *
     * @param isEnabled True to force hi-perf mode, false to leave it up to acquired wifiLocks.
     * @return True for success, false for failure (failure turns forcing mode off)
     */
    public boolean forceHiPerfMode(boolean isEnabled) {
        mForceHiPerfMode = isEnabled;
        mForceLowLatencyMode = false;
        if (!updateOpMode()) {
            Slog.e(TAG, "Failed to force hi-perf mode, returning to normal mode");
            mForceHiPerfMode = false;
            return false;
        }
        return true;
    }

    /**
     * Method Used for shell command support
     *
     * @param isEnabled True to force low-latency mode, false to leave it up to acquired wifiLocks.
     * @return True for success, false for failure (failure turns forcing mode off)
     */
    public boolean forceLowLatencyMode(boolean isEnabled) {
        mForceLowLatencyMode = isEnabled;
        mForceHiPerfMode = false;
        if (!updateOpMode()) {
            Slog.e(TAG, "Failed to force low-latency mode, returning to normal mode");
            mForceLowLatencyMode = false;
            return false;
        }
        return true;
    }

    /**
     * Handler for screen state (on/off) changes
     */
    public void handleScreenStateChanged(boolean screenOn) {
        if (mVerboseLoggingEnabled) {
            Slog.d(TAG, "handleScreenStateChanged: screenOn = " + screenOn);
        }

        mScreenOn = screenOn;

        // Update the running mode
        updateOpMode();
    }

    private static boolean isValidLockMode(int lockMode) {
        if (lockMode != WifiManager.WIFI_MODE_FULL
                && lockMode != WifiManager.WIFI_MODE_SCAN_ONLY
                && lockMode != WifiManager.WIFI_MODE_FULL_HIGH_PERF
                && lockMode != WifiManager.WIFI_MODE_FULL_LOW_LATENCY) {
            return false;
        }
        return true;
    }

    private void addUidToLlWatchList(int uid) {
        UidRec uidRec = mLowLatencyUidWatchList.get(uid);
        if (uidRec != null) {
            uidRec.mLockCount++;
        } else {
            uidRec = new UidRec(uid);
            uidRec.mLockCount = 1;
            mLowLatencyUidWatchList.put(uid, uidRec);

            // Now check if the uid is running in foreground
            if (mFrameworkFacade.isAppForeground(uid)) {
                uidRec.mIsFg = true;
            }
        }
    }

    private void removeUidFromLlWatchList(int uid) {
        UidRec uidRec = mLowLatencyUidWatchList.get(uid);
        if (uidRec == null) {
            Slog.e(TAG, "Failed to find uid in low-latency watch list");
            return;
        }

        if (uidRec.mLockCount > 0) {
            uidRec.mLockCount--;
        } else {
            Slog.e(TAG, "Error, uid record conatains no locks");
        }
        if (uidRec.mLockCount == 0) {
            mLowLatencyUidWatchList.remove(uid);
        }
    }

    private void addWsToLlWatchList(WorkSource ws) {
        int wsSize = ws.size();
        for (int i = 0; i < wsSize; i++) {
            final int uid = ws.get(i);
            addUidToLlWatchList(uid);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = workChain.getAttributionUid();
                addUidToLlWatchList(uid);
            }
        }
    }

    private void removeWsFromLlWatchList(WorkSource ws) {
        int wsSize = ws.size();
        for (int i = 0; i < wsSize; i++) {
            final int uid = ws.get(i);
            removeUidFromLlWatchList(uid);
        }

        final List<WorkChain> workChains = ws.getWorkChains();
        if (workChains != null) {
            for (int i = 0; i < workChains.size(); ++i) {
                final WorkChain workChain = workChains.get(i);
                final int uid = workChain.getAttributionUid();
                removeUidFromLlWatchList(uid);
            }
        }
    }

    private synchronized boolean addLock(WifiLock lock) {
        if (mVerboseLoggingEnabled) {
            Slog.d(TAG, "addLock: " + lock);
        }

        if (findLockByBinder(lock.getBinder()) != null) {
            if (mVerboseLoggingEnabled) {
                Slog.d(TAG, "attempted to add a lock when already holding one");
            }
            return false;
        }

        mWifiLocks.add(lock);

        boolean lockAdded = false;
        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.noteFullWifiLockAcquiredFromSource(lock.mWorkSource);
            switch(lock.mMode) {
                case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                    ++mFullHighPerfLocksAcquired;
                    break;
                case WifiManager.WIFI_MODE_FULL_LOW_LATENCY:
                    addWsToLlWatchList(lock.getWorkSource());
                    ++mFullLowLatencyLocksAcquired;
                    break;
                default:
                    // Do nothing
                    break;
            }
            lockAdded = true;

            // Recalculate the operating mode
            updateOpMode();
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return lockAdded;
    }

    private synchronized WifiLock removeLock(IBinder binder) {
        WifiLock lock = findLockByBinder(binder);
        if (lock != null) {
            mWifiLocks.remove(lock);
            lock.unlinkDeathRecipient();
        }
        return lock;
    }

    private synchronized boolean releaseLock(IBinder binder) {
        WifiLock wifiLock = removeLock(binder);
        if (wifiLock == null) {
            // attempting to release a lock that is not active.
            return false;
        }

        if (mVerboseLoggingEnabled) {
            Slog.d(TAG, "releaseLock: " + wifiLock);
        }

        long ident = Binder.clearCallingIdentity();
        try {
            mBatteryStats.noteFullWifiLockReleasedFromSource(wifiLock.mWorkSource);
            switch(wifiLock.mMode) {
                case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                    ++mFullHighPerfLocksReleased;
                    break;
                case WifiManager.WIFI_MODE_FULL_LOW_LATENCY:
                    removeWsFromLlWatchList(wifiLock.getWorkSource());
                    ++mFullLowLatencyLocksReleased;
                    break;
                default:
                    // Do nothing
                    break;
            }

            // Recalculate the operating mode
            updateOpMode();
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return true;
    }

    private synchronized boolean updateOpMode() {
        final int newLockMode = getStrongestLockMode();

        if (newLockMode == mCurrentOpMode) {
            // No action is needed
            return true;
        }

        if (mVerboseLoggingEnabled) {
            Slog.d(TAG, "Current opMode: " + mCurrentOpMode + " New LockMode: " + newLockMode);
        }

        // Otherwise, we need to change current mode, first reset it to normal
        switch (mCurrentOpMode) {
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                if (!mClientModeImpl.setPowerSave(true)) {
                    Slog.e(TAG, "Failed to reset the OpMode from hi-perf to Normal");
                    return false;
                }
                break;

            case WifiManager.WIFI_MODE_FULL_LOW_LATENCY:
                if (!setLowLatencyMode(false)) {
                    Slog.e(TAG, "Failed to reset the OpMode from low-latency to Normal");
                    return false;
                }
                break;

            case WifiManager.WIFI_MODE_NO_LOCKS_HELD:
            default:
                // No action
                break;
        }

        // Set the current mode, before we attempt to set the new mode
        mCurrentOpMode = WifiManager.WIFI_MODE_NO_LOCKS_HELD;

        // Now switch to the new opMode
        switch (newLockMode) {
            case WifiManager.WIFI_MODE_FULL_HIGH_PERF:
                if (!mClientModeImpl.setPowerSave(false)) {
                    Slog.e(TAG, "Failed to set the OpMode to hi-perf");
                    return false;
                }
                break;

            case WifiManager.WIFI_MODE_FULL_LOW_LATENCY:
                if (!setLowLatencyMode(true)) {
                    Slog.e(TAG, "Failed to set the OpMode to low-latency");
                    return false;
                }
                break;

            case WifiManager.WIFI_MODE_NO_LOCKS_HELD:
                // No action
                break;

            default:
                // Invalid mode, don't change currentOpMode , and exit with error
                Slog.e(TAG, "Invalid new opMode: " + newLockMode);
                return false;
        }

        // Now set the mode to the new value
        mCurrentOpMode = newLockMode;
        return true;
    }

    private int getLowLatencyModeSupport() {
        if (mLatencyModeSupport == LOW_LATENCY_SUPPORT_UNDEFINED
                && mClientModeImplChannel != null) {
            long supportedFeatures =
                    mClientModeImpl.syncGetSupportedFeatures(mClientModeImplChannel);
            if (supportedFeatures != 0) {
                if ((supportedFeatures & WifiManager.WIFI_FEATURE_LOW_LATENCY) != 0) {
                    mLatencyModeSupport = LOW_LATENCY_SUPPORTED;
                } else {
                    mLatencyModeSupport = LOW_LATENCY_NOT_SUPPORTED;
                }
            }
        }

        return mLatencyModeSupport;
    }

    private boolean setLowLatencyMode(boolean enabled) {
        int lowLatencySupport = getLowLatencyModeSupport();

        if (lowLatencySupport == LOW_LATENCY_SUPPORTED) {
            return mClientModeImpl.setLowLatencyMode(enabled);
        } else if (lowLatencySupport == LOW_LATENCY_NOT_SUPPORTED) {
            // Since low-latency mode is not supported, use power save instead
            // Note: low-latency mode enabled ==> power-save disabled
            if (mVerboseLoggingEnabled) {
                Slog.d(TAG, "low-latency is not supported, using power-save instead");
            }

            return mClientModeImpl.setPowerSave(!enabled);
        } else {
            // Support undefined, no need to attempt either functions
            return false;
        }
    }

    private synchronized WifiLock findLockByBinder(IBinder binder) {
        for (WifiLock lock : mWifiLocks) {
            if (lock.getBinder() == binder) {
                return lock;
            }
        }
        return null;
    }

    private int countFgLowLatencyUids() {
        int uidCount = 0;
        int listSize = mLowLatencyUidWatchList.size();
        for (int idx = 0; idx < listSize; idx++) {
            UidRec uidRec = mLowLatencyUidWatchList.valueAt(idx);
            if (uidRec.mIsFg) {
                uidCount++;
            }
        }
        return uidCount;
    }

    protected void dump(PrintWriter pw) {
        pw.println("Locks acquired: "
                + mFullHighPerfLocksAcquired + " full high perf, "
                + mFullLowLatencyLocksAcquired + " full low latency");
        pw.println("Locks released: "
                + mFullHighPerfLocksReleased + " full high perf, "
                + mFullLowLatencyLocksReleased + " full low latency");

        pw.println();
        pw.println("Locks held:");
        for (WifiLock lock : mWifiLocks) {
            pw.print("    ");
            pw.println(lock);
        }
    }

    protected void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            mVerboseLoggingEnabled = true;
        } else {
            mVerboseLoggingEnabled = false;
        }
    }

    /**
     * Handles interaction with ClientModeImpl
     */
    private class ClientModeImplInterfaceHandler extends WifiHandler {
        private WifiAsyncChannel mCmiChannel;

        ClientModeImplInterfaceHandler(Looper looper) {
            super(TAG, looper);
            mCmiChannel = mFrameworkFacade.makeWifiAsyncChannel(TAG);
            mCmiChannel.connect(mContext, this, mClientModeImpl.getHandler());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED: {
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        mClientModeImplChannel = mCmiChannel;
                    } else {
                        Slog.e(TAG, "ClientModeImpl connection failure, error=" + msg.arg1);
                        mClientModeImplChannel = null;
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    Slog.e(TAG, "ClientModeImpl channel lost, msg.arg1 =" + msg.arg1);
                    mClientModeImplChannel = null;
                    //Re-establish connection
                    mCmiChannel.connect(mContext, this, mClientModeImpl.getHandler());
                    break;
                }
                default: {
                    Slog.d(TAG, "ClientModeImplInterfaceHandler.handleMessage ignoring msg=" + msg);
                    break;
                }
            }
        }
    }

    private class WifiLock implements IBinder.DeathRecipient {
        String mTag;
        int mUid;
        IBinder mBinder;
        int mMode;
        WorkSource mWorkSource;

        WifiLock(int lockMode, String tag, IBinder binder, WorkSource ws) {
            mTag = tag;
            mBinder = binder;
            mUid = Binder.getCallingUid();
            mMode = lockMode;
            mWorkSource = ws;
            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        protected WorkSource getWorkSource() {
            return mWorkSource;
        }

        protected int getUid() {
            return mUid;
        }

        protected IBinder getBinder() {
            return mBinder;
        }

        public void binderDied() {
            releaseLock(mBinder);
        }

        public void unlinkDeathRecipient() {
            mBinder.unlinkToDeath(this, 0);
        }

        public String toString() {
            return "WifiLock{" + this.mTag + " type=" + this.mMode + " uid=" + mUid
                    + " workSource=" + mWorkSource + "}";
        }
    }

    private class UidRec {
        final int mUid;
        // Count of locks owned or co-owned by this UID
        int mLockCount;
        // Is this UID running in foreground
        boolean mIsFg;

        UidRec(int uid) {
            mUid = uid;
        }
    }
}
