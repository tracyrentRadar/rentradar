package com.rentradar.android.data.remote.api;

import com.rentradar.android.data.remote.dto.PropertyDtos;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface PropertyApi {

    String PATH_PREFIX = "api/v1/properties";

    @POST(PATH_PREFIX)
    Call<PropertyDtos.PropertyResponse> create(@Body PropertyDtos.CreatePropertyRequest body);
}