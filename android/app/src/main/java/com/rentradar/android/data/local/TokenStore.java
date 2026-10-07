package com.rentradar.android.data.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.rentradar.android.data.remote.dto.AuthDtos;

import java.io.IOException;
import java.security.GeneralSecurityException;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;

/** The only place tokens are read or written. */
@Singleton
public class TokenStore {

    private static final String TAG = "TokenStore";
    private static final String FILE = "rentradar_secure_prefs";
    private static final String K_ACCESS = "access_token";
    private static final String K_REFRESH = "refresh_token";
    private static final String K_USER_ID = "user_id";
    private static final String K_MARKET = "market_id";

    private final SharedPreferences prefs;

    @Inject
    public TokenStore(@ApplicationContext Context context) {
        this.prefs = open(context, true);
    }

    private static SharedPreferences open(Context context, boolean recoverOnce) {
        try {
            MasterKey key = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    context,
                    FILE,
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (GeneralSecurityException | IOException e) {
            if (recoverOnce) {
                // Keystore reset or a half written file. Throw it away and start
                // clean, the worst case is the user signs in again.
                Log.w(TAG, "Secure store unreadable, recreating", e);
                context.deleteSharedPreferences(FILE);
                return open(context, false);
            }
            throw new IllegalStateException("Secure token store unavailable", e);
        }
    }

    public synchronized void save(AuthDtos.AuthResponse res) {
        SharedPreferences.Editor e = prefs.edit();
        e.putString(K_ACCESS, res.accessToken);
        // Refresh tokens rotate on every use. If none came back, keep the one
        // we hold rather than wiping it.
        if (res.refreshToken != null) {
            e.putString(K_REFRESH, res.refreshToken);
        }
        if (res.user != null) {
            e.putString(K_USER_ID, res.user.id);
            e.putString(K_MARKET, res.user.marketId);
        }
        e.apply();
    }

    @Nullable
    public synchronized String accessToken() {
        return prefs.getString(K_ACCESS, null);
    }

    @Nullable
    public synchronized String refreshToken() {
        return prefs.getString(K_REFRESH, null);
    }

    @Nullable
    public synchronized String marketId() {
        return prefs.getString(K_MARKET, null);
    }

    public synchronized boolean isSignedIn() {
        return prefs.getString(K_REFRESH, null) != null;
    }

    public synchronized void clear() {
        prefs.edit().clear().apply();
    }
}