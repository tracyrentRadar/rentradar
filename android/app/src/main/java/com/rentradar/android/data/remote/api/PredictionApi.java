package com.rentradar.android.data.remote.api;

import com.rentradar.android.data.remote.dto.PredictionDtos;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * Note the two return types. POST answers with the four-block DTO; the GETs
 * answer with the stored document, which is a flat shape. They are different
 * types on purpose, not an oversight.
 */
public interface PredictionApi {

    String PATH_PREFIX = "api/v1/predictions";

    @POST(PATH_PREFIX)
    Call<PredictionDtos.PredictionResponse> predict(@Body PredictionDtos.PredictionRequest body);

    @GET(PATH_PREFIX)
    Call<List<PredictionDtos.StoredPrediction>> history();

    @GET(PATH_PREFIX + "/{id}")
    Call<PredictionDtos.StoredPrediction> byId(@Path("id") String id);
}