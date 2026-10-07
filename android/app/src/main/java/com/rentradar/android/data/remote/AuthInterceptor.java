package com.rentradar.android.data.remote;

import androidx.annotation.NonNull;

import com.rentradar.android.data.local.TokenStore;
import com.rentradar.android.data.remote.api.AuthApi;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

@Singleton
public class AuthInterceptor implements Interceptor {

    private final TokenStore tokens;

    @Inject
    public AuthInterceptor(TokenStore tokens) {
        this.tokens = tokens;
    }

    /**
     * Login, register, refresh and logout must never carry a bearer token.
     * Otherwise a stale token rides along and a plain wrong-password 401 gets
     * mistaken for an expiry by the authenticator.
     */
    static boolean isAuthEndpoint(Request request) {
        return request.url().encodedPath().contains("/" + AuthApi.PATH_PREFIX);
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        String token = tokens.accessToken();
        if (token == null || isAuthEndpoint(request)) {
            return chain.proceed(request);
        }
        return chain.proceed(request.newBuilder()
                .header("Authorization", "Bearer " + token)
                .build());
    }
}