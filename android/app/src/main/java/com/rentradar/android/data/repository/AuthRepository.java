package com.rentradar.android.data.repository;

import androidx.annotation.NonNull;

import com.rentradar.android.data.Result;
import com.rentradar.android.data.local.TokenStore;
import com.rentradar.android.data.remote.ErrorMapper;
import com.rentradar.android.data.remote.api.AuthApi;
import com.rentradar.android.data.remote.dto.AuthDtos;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Singleton;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Owns the auth calls and the token side effect. Nothing above this class ever
 * touches TokenStore on a successful sign in.
 */
@Singleton
public class AuthRepository {

    private final AuthApi api;
    private final TokenStore tokens;
    private final ErrorMapper errors;

    @Inject
    public AuthRepository(AuthApi api, TokenStore tokens, ErrorMapper errors) {
        this.api = api;
        this.tokens = tokens;
        this.errors = errors;
    }

    public void login(String email, String password,
                      Result.Listener<AuthDtos.UserSummary> listener) {
        api.login(new AuthDtos.LoginRequest(email, password))
                .enqueue(handler(listener, "Email or password is incorrect"));
    }

    public void register(String email, String password, String role,
                         String marketId, String locale,
                         Result.Listener<AuthDtos.UserSummary> listener) {
        api.register(new AuthDtos.RegisterRequest(email, password, role, marketId, locale))
                .enqueue(handler(listener, "Could not create the account"));
    }

    public void signOut() {
        String refresh = tokens.refreshToken();
        tokens.clear();
        if (refresh == null) {
            return;
        }
        // Best effort. The local tokens are already gone either way.
        api.logout(new AuthDtos.RefreshRequest(refresh)).enqueue(new Callback<Void>() {
            @Override public void onResponse(@NonNull Call<Void> c, @NonNull Response<Void> r) { }
            @Override public void onFailure(@NonNull Call<Void> c, @NonNull Throwable t) { }
        });
    }

    public boolean isSignedIn() {
        return tokens.isSignedIn();
    }

    public TokenStore tokens() {
        return tokens;
    }

    private Callback<AuthDtos.AuthResponse> handler(
            Result.Listener<AuthDtos.UserSummary> listener, String fallback) {

        return new Callback<AuthDtos.AuthResponse>() {
            @Override
            public void onResponse(@NonNull Call<AuthDtos.AuthResponse> call,
                                   @NonNull Response<AuthDtos.AuthResponse> response) {
                if (!response.isSuccessful() || response.body() == null
                        || response.body().accessToken == null) {
                    listener.onResult(Result.error(errors.map(response, fallback)));
                    return;
                }
                tokens.save(response.body());
                listener.onResult(Result.success(response.body().user));
            }

            @Override
            public void onFailure(@NonNull Call<AuthDtos.AuthResponse> call,
                                  @NonNull Throwable t) {
                listener.onResult(Result.error(t instanceof IOException
                        ? "Could not reach the server. Check that it is running."
                        : "Something went wrong. Try again."));
            }
        };
    }
}