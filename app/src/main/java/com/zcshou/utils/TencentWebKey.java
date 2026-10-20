package com.zcshou.utils;

import android.content.Context;

import androidx.preference.PreferenceManager;

import com.zcshou.gogogo.BuildConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** A per-request snapshot of the WebService key. Never log the key itself. */
public final class TencentWebKey {
    public final String value;
    public final String diagnostic;

    private TencentWebKey(String value, String source) {
        this.value = value;
        this.diagnostic = "keySource=" + source + " keyFingerprint=" + fingerprint(value);
    }

    public static TencentWebKey load(Context context) {
        return resolve(PreferenceManager.getDefaultSharedPreferences(context)
                .getString("setting_tencent_key", ""), BuildConfig.TENCENT_MAP_KEY);
    }

    static TencentWebKey resolve(String setting, String bundled) {
        String configured = setting == null ? "" : setting.trim();
        return configured.isEmpty()
                ? new TencentWebKey(bundled == null ? "" : bundled.trim(), "build")
                : new TencentWebKey(configured, "settings");
    }

    private static String fingerprint(String value) {
        if (value.isEmpty()) return "empty";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                result.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
