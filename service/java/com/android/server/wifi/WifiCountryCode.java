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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wifi.resources.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Provide functions for making changes to WiFi country code.
 * This Country Code is from MCC or phone default setting. This class sends Country Code
 * to driver through wpa_supplicant when ClientModeImpl marks current state as ready
 * using setReadyForChange(true).
 */
public class WifiCountryCode {
    private static final String TAG = "WifiCountryCode";
    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final WifiNative mWifiNative;
    private boolean DBG = false;
    private boolean mReady = false;
    private static final SimpleDateFormat FORMATTER = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    private String mDefaultCountryCode = null;
    private String mTelephonyCountryCode = null;
    private String mScanCountryCode = null;
    private String mScanCountryTimestamp = null;
    private String mDriverCountryCode = null;
    private String mTelephonyCountryTimestamp = null;
    private String mDriverCountryTimestamp = null;
    private String mReadyTimestamp = null;
    private boolean mForceCountryCode = false;

    /** 802.11d Country information element. */
    private static final int EID_COUNTRY = 7;
    /** Access points that must report a country before it is used. */
    private static final int MIN_SCAN_COUNTRY_BSSIDS = 2;

    public WifiCountryCode(
            Context context,
            Handler handler,
            WifiNative wifiNative,
            String oemDefaultCountryCode) {
        mContext = context;
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mWifiNative = wifiNative;

        if (!TextUtils.isEmpty(oemDefaultCountryCode)) {
            mDefaultCountryCode = oemDefaultCountryCode.toUpperCase(Locale.US);
        }
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String countryCode = intent.getStringExtra(TelephonyManager.EXTRA_NETWORK_COUNTRY);
                Log.d(TAG, "Country code changed");
                setCountryCodeAndUpdate(countryCode);
            }}, new IntentFilter(TelephonyManager.ACTION_NETWORK_COUNTRY_CHANGED), null, handler);

        Log.d(TAG, "mDefaultCountryCode " + mDefaultCountryCode);
    }

    /**
     * Enable verbose logging for WifiCountryCode.
     */
    public void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            DBG = true;
        } else {
            DBG = false;
        }
    }

    private void initializeTelephonyCountryCodeIfNeeded() {
        // If we don't have telephony country code set yet, poll it.
        if (mTelephonyCountryCode == null) {
            Log.d(TAG, "Reading country code from telephony");
            setCountryCode(mTelephonyManager.getNetworkCountryIso());
        }
    }

    /**
     * Change the state to indicates if wpa_supplicant is ready to handle country code changing
     * request or not.
     * We call native code to request country code changes only when wpa_supplicant is
     * started but not yet L2 connected.
     */
    public synchronized void setReadyForChange(boolean ready) {
        mReady = ready;
        mReadyTimestamp = FORMATTER.format(new Date(System.currentTimeMillis()));
        // We are ready to set country code now.
        // We need to post pending country code request.
        if (mReady) {
            updateCountryCode();
        }
    }

    /**
     * Enable force-country-code mode
     * This is for forcing a country using cmd wifi from adb shell
     * This is for test purpose only and we should disallow any update from
     * telephony in this mode
     * @param countryCode The forced two-letter country code
     */
    synchronized void enableForceCountryCode(String countryCode) {
        if (TextUtils.isEmpty(countryCode)) {
            Log.d(TAG, "Fail to force country code because the received country code is empty");
            return;
        }
        mForceCountryCode = true;
        mTelephonyCountryCode = countryCode.toUpperCase(Locale.US);

        // If wpa_supplicant is ready we set the country code now, otherwise it will be
        // set once wpa_supplicant is ready.
        if (mReady) {
            updateCountryCode();
        } else {
            Log.d(TAG, "skip update supplicant not ready yet");
        }
    }

    /**
     * Disable force-country-code mode
     */
    synchronized void disableForceCountryCode() {
        mForceCountryCode = false;
        mTelephonyCountryCode = null;

        // If wpa_supplicant is ready we set the country code now, otherwise it will be
        // set once wpa_supplicant is ready.
        if (mReady) {
            updateCountryCode();
        } else {
            Log.d(TAG, "skip update supplicant not ready yet");
        }
    }

    private boolean setCountryCode(String countryCode) {
        if (mForceCountryCode) {
            Log.d(TAG, "Telephony Country code ignored due to force-country-code mode");
            return false;
        }
        Log.d(TAG, "Set telephony country code to: " + countryCode);
        mTelephonyCountryTimestamp = FORMATTER.format(new Date(System.currentTimeMillis()));

        // Empty country code.
        if (TextUtils.isEmpty(countryCode)) {
            if (mContext.getResources()
                        .getBoolean(R.bool.config_wifi_revert_country_code_on_cellular_loss)) {
                Log.d(TAG, "Received empty country code, reset to default country code");
                mTelephonyCountryCode = null;
            }
        } else {
            mTelephonyCountryCode = countryCode.toUpperCase(Locale.US);
        }
        return true;
    }

    /**
     * Handle country code change request.
     * @param countryCode The country code intended to set.
     * This is supposed to be from Telephony service.
     * otherwise we think it is from other applications.
     * @return Returns true if the country code passed in is acceptable.
     */
    private boolean setCountryCodeAndUpdate(String countryCode) {
        if (!setCountryCode(countryCode)) return false;
        // If wpa_supplicant is ready we set the country code now, otherwise it will be
        // set once wpa_supplicant is ready.
        if (mReady) {
            updateCountryCode();
        } else {
            Log.d(TAG, "skip update supplicant not ready yet");
        }

        return true;
    }

    /**
     * Derive the country code from the 802.11d Country element of nearby access points.
     * For devices without telephony: a country is used when at least
     * MIN_SCAN_COUNTRY_BSSIDS access points report it and more than half of all access
     * points with a Country element agree. The last detected country is kept until another
     * one wins. Telephony and force-country-code take precedence.
     * @param scanResults results of a full band scan
     */
    public synchronized void updateCountryCodeFromScanResults(ScanResult[] scanResults) {
        if (scanResults == null || !mContext.getResources()
                .getBoolean(R.bool.config_wifiCountryCodeFromScanResults)) {
            return;
        }
        Map<String, Set<String>> bssidsByCountry = new HashMap<>();
        int total = 0;
        for (ScanResult result : scanResults) {
            String country = getCountryFromInformationElements(result.informationElements);
            if (country == null || result.BSSID == null) continue;
            Set<String> bssids = bssidsByCountry.get(country);
            if (bssids == null) {
                bssids = new HashSet<>();
                bssidsByCountry.put(country, bssids);
            }
            if (bssids.add(result.BSSID)) total++;
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Set<String>> entry : bssidsByCountry.entrySet()) {
            if (entry.getValue().size() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue().size();
            }
        }
        if (best == null || bestCount < MIN_SCAN_COUNTRY_BSSIDS || bestCount * 2 <= total) {
            if (DBG && total > 0) Log.d(TAG, "No scan country majority: " + bssidsByCountry);
            return;
        }
        if (best.equals(mScanCountryCode)) return;
        Log.d(TAG, "Country code from scan results: " + best + " (" + bestCount + " of "
                + total + " access points)");
        mScanCountryCode = best;
        mScanCountryTimestamp = FORMATTER.format(new Date(System.currentTimeMillis()));
        if (mReady) {
            updateCountryCode();
        } else if (mDriverCountryCode == null) {
            // Already connected before any country was known (right after boot): the driver
            // is still on its worldwide default and would only get the code at the next
            // disconnect. Send it now; drivers that set it without a forced disassociation
            // keep the connection and the new channels become usable for roaming.
            Log.d(TAG, "Driver has no country code yet, sending scan country while connected");
            updateCountryCode();
        } else {
            Log.d(TAG, "skip update supplicant not ready yet");
        }
    }

    private static String getCountryFromInformationElements(ScanResult.InformationElement[] ies) {
        if (ies == null) return null;
        for (ScanResult.InformationElement ie : ies) {
            if (ie == null || ie.id != EID_COUNTRY || ie.bytes == null || ie.bytes.length < 2) {
                continue;
            }
            char c0 = Character.toUpperCase((char) ie.bytes[0]);
            char c1 = Character.toUpperCase((char) ie.bytes[1]);
            if (c0 < 'A' || c0 > 'Z' || c1 < 'A' || c1 > 'Z') return null;
            String country = "" + c0 + c1;
            // "XX" and "ZZ" are used by access points without a configured country.
            if (country.equals("XX") || country.equals("ZZ")) return null;
            return country;
        }
        return null;
    }

    /**
     * Method to get the Country Code that was sent to wpa_supplicant.
     *
     * @return Returns the local copy of the Country Code that was sent to the driver upon
     * setReadyForChange(true).
     * If wpa_supplicant was never started, this may be null even if a SIM reported a valid
     * country code.
     * Returns null if no Country Code was sent to driver.
     */
    @VisibleForTesting
    public synchronized String getCountryCodeSentToDriver() {
        return mDriverCountryCode;
    }

    /**
     * Method to return the currently reported Country Code from the SIM or phone default setting.
     *
     * @return The currently reported Country Code from the SIM. If there is no Country Code
     * reported from SIM, a phone default Country Code will be returned.
     * Returns null when there is no Country Code available.
     */
    public synchronized String getCountryCode() {
        return pickCountryCode();
    }

    /**
     * Method to dump the current state of this WifiCounrtyCode object.
     */
    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mRevertCountryCodeOnCellularLoss: "
                + mContext.getResources().getBoolean(
                        R.bool.config_wifi_revert_country_code_on_cellular_loss));
        pw.println("mDefaultCountryCode: " + mDefaultCountryCode);
        pw.println("mDriverCountryCode: " + mDriverCountryCode);
        pw.println("mTelephonyCountryCode: " + mTelephonyCountryCode);
        pw.println("mTelephonyCountryTimestamp: " + mTelephonyCountryTimestamp);
        pw.println("mScanCountryCode: " + mScanCountryCode);
        pw.println("mScanCountryTimestamp: " + mScanCountryTimestamp);
        pw.println("mDriverCountryTimestamp: " + mDriverCountryTimestamp);
        pw.println("mReadyTimestamp: " + mReadyTimestamp);
        pw.println("mReady: " + mReady);
    }

    private void updateCountryCode() {
        String country = pickCountryCode();
        Log.d(TAG, "updateCountryCode to " + country);

        // We do not check if the country code equals the current one.
        // There are two reasons:
        // 1. Wpa supplicant may silently modify the country code.
        // 2. If Wifi restarted therefoere wpa_supplicant also restarted,
        // the country code counld be reset to '00' by wpa_supplicant.
        if (country != null) {
            setCountryCodeNative(country);
        }
        // We do not set country code if there is no candidate. This is reasonable
        // because wpa_supplicant usually starts with an international safe country
        // code setting: '00'.
    }

    private String pickCountryCode() {

        initializeTelephonyCountryCodeIfNeeded();

        if (mTelephonyCountryCode != null) {
            return mTelephonyCountryCode;
        }
        if (mScanCountryCode != null) {
            return mScanCountryCode;
        }
        if (mDefaultCountryCode != null) {
            return mDefaultCountryCode;
        }
        // If there is no candidate country code we will return null.
        return null;
    }

    private boolean setCountryCodeNative(String country) {
        mDriverCountryTimestamp = FORMATTER.format(new Date(System.currentTimeMillis()));
        if (mWifiNative.setCountryCode(mWifiNative.getClientInterfaceName(), country)) {
            Log.d(TAG, "Succeeded to set country code to: " + country);
            mDriverCountryCode = country;
            return true;
        }
        Log.d(TAG, "Failed to set country code to: " + country);
        return false;
    }
}

