package com.rentradar.android.data.remote.api;

import com.rentradar.android.data.remote.dto.PredictionDtos;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * Note the two return types. POST answers with the four-block DTO; the GETs
 * answer with a history row. They are different types on purpose.
 */
public interface PredictionApi {

    String PATH_PREFIX = "api/v1/predictions";

    @POST(PATH_PREFIX)
    Call<PredictionDtos.PredictionResponse> predict(@Body PredictionDtos.PredictionRequest body);

    /**
     * Newest first. limit exists so Home can ask for the three it shows rather
     * than pulling a whole term of history to display three rows.
     */
    @GET(PATH_PREFIX)
    Call<List<PredictionDtos.Summary>> history(@Query("limit") int limit);

    @GET(PATH_PREFIX + "/{id}")
    Call<PredictionDtos.Summary> byId(@Path("id") String id);
}