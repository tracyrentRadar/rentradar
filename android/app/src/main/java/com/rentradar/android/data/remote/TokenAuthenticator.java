package com.rentradar.android.data.remote;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.rentradar.android.data.SessionManager;
import com.rentradar.android.data.local.TokenStore;
import com.rentradar.android.data.remote.api.AuthApi;
import com.rentradar.android.data.remote.dto.AuthDtos;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import okhttp3.Authenticator;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route;

/**
 * OkHttp calls this when a request comes back 401. It refreshes once and
 * replays the original. The refresh goes out on a separate client with no
 * interceptor and no authenticator, so it cannot recurse.
 */
@Singleton
public class TokenAuthenticator implements Authenticator {

    private static final String TAG = "TokenAuthenticator";
    private static final int MAX_ATTEMPTS = 1;

    private final TokenStore tokens;
    private final SessionManager session;
    private final Provider<AuthApi> refreshApi;

    @Inject
    public TokenAuthenticator(TokenStore tokens,
                              SessionManager session,
                              @Named("refresh") Provider<AuthApi> refreshApi) {
        this.tokens = tokens;
        this.session = session;
        this.refreshApi = refreshApi;
    }

    @Nullable
    @Override
    public Request authenticate(@Nullable Route route, @NonNull Response response) {
        if (AuthInterceptor.isAuthEndpoint(response.request())) {
            return null;
        }
        if (attempts(response) > MAX_ATTEMPTS) {
            return null;
        }

        synchronized (this) {
            String sent = response.request().header("Authorization");
            String current = tokens.accessToken();

            // Another request already refreshed while this one was in flight.
            if (current != null && !("Bearer " + current).equals(sent)) {
                return retryWith(response, current);
            }

            String refresh = tokens.refreshToken();
            if (refresh == null) {
                return null;
            }

            try {
                retrofit2.Response<AuthDtos.AuthResponse> result =
                        refreshApi.get().refresh(new AuthDtos.RefreshRequest(refresh)).execute();

                if (!result.isSuccessful() || result.body() == null
                        || result.body().accessToken == null) {
                    // A rejected rotation revokes every session for the account,
                    // so there is nothing left to salvage locally.
                    Log.w(TAG, "Refresh rejected with " + result.code() + ", signing out");
                    tokens.clear();
                    session.notifyExpired();
                    return null;
                }

                tokens.save(result.body());
                return retryWith(response, result.body().accessToken);

            } catch (IOException e) {
                // Offline is not expired. Keep the tokens, let the call fail.
                Log.w(TAG, "Refresh could not reach the server", e);
                return null;
            }
        }
    }

    private static Request retryWith(Response response, String accessToken) {
        return response.request().newBuilder()
                .header("Authorization", "Bearer " + accessToken)
                .build();
    }

    private static int attempts(Response response) {
        int count = 1;
        Response prior = response.priorResponse();
        while (prior != null) {
            count++;
            prior = prior.priorResponse();
        }
        return count;
    }
}