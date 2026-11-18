/*
 * Copyright (C) 2026 The LineageOS Project
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

package com.android.wifitrackerlib;

import static com.android.wifitrackerlib.Utils.isScanResultSecuritySupported;

import android.content.Context;
import android.content.res.Resources;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;

import androidx.annotation.NonNull;

import java.util.Collection;

/**
 * Tells whether the device can use the security an access point offers. Reads the device
 * capabilities once: SAE, Suite B and OWE from WifiManager, the SHA256 key management suites
 * from the framework resource config_wifiSha256AkmSupported (missing means supported).
 */
public final class WifiSecuritySupport {
    private final boolean mIsSha256AkmSupported;
    private final boolean mIsWpa3SaeSupported;
    private final boolean mIsWpa3SuiteBSupported;
    private final boolean mIsEnhancedOpenSupported;

    public WifiSecuritySupport(@NonNull Context context, @NonNull WifiManager wifiManager) {
        final Resources res = context.getResources();
        final int sha256Id = res.getIdentifier(
                "config_wifiSha256AkmSupported", "bool", "android");
        mIsSha256AkmSupported = sha256Id == 0 || res.getBoolean(sha256Id);
        mIsWpa3SaeSupported = wifiManager.isWpa3SaeSupported();
        mIsWpa3SuiteBSupported = wifiManager.isWpa3SuiteBSupported();
        mIsEnhancedOpenSupported = wifiManager.isEnhancedOpenSupported();
    }

    /** Returns false if the device cannot use any key management the AP offers. */
    public boolean isSupported(@NonNull ScanResult scan) {
        return isScanResultSecuritySupported(scan, mIsSha256AkmSupported, mIsWpa3SaeSupported,
                mIsWpa3SuiteBSupported, mIsEnhancedOpenSupported);
    }

    /**
     * Returns false if none of the APs can be used. An empty collection (out of range)
     * counts as supported.
     */
    public boolean isSupported(@NonNull Collection<ScanResult> scans) {
        return scans.isEmpty() || scans.stream().anyMatch(this::isSupported);
    }
}
