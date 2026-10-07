package com.rentradar.android.data.remote.api;

import com.rentradar.android.data.remote.dto.AuthDtos;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface AuthApi {

    String PATH_PREFIX = "api/v1/auth/";

    @POST(PATH_PREFIX + "register")
    Call<AuthDtos.AuthResponse> register(@Body AuthDtos.RegisterRequest body);

    @POST(PATH_PREFIX + "login")
    Call<AuthDtos.AuthResponse> login(@Body AuthDtos.LoginRequest body);

    @POST(PATH_PREFIX + "refresh")
    Call<AuthDtos.AuthResponse> refresh(@Body AuthDtos.RefreshRequest body);

    @POST(PATH_PREFIX + "logout")
    Call<Void> logout(@Body AuthDtos.RefreshRequest body);
}