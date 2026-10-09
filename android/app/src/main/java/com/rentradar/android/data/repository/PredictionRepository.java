package com.rentradar.android.data.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.rentradar.android.data.Result;
import com.rentradar.android.data.remote.ErrorMapper;
import com.rentradar.android.data.remote.api.PredictionApi;
import com.rentradar.android.data.remote.api.PropertyApi;
import com.rentradar.android.data.remote.dto.PredictionDtos;
import com.rentradar.android.data.remote.dto.PropertyDtos;

import java.io.IOException;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Two network calls behave as one operation here: the listing is submitted,
 * then the returned id is priced. The screen asks once and is told once.
 *
 * Retrofit's enqueue delivers on the main thread on Android, which is what
 * Result.Listener promises, so nothing is posted by hand.
 *
 * Error text comes from ErrorMapper, so a validation failure shows the
 * server's own field message rather than a generic sentence invented here.
 * The fallbacks below are only reached when the body is empty or unparseable.
 */
@Singleton
public class PredictionRepository {

    private final PropertyApi properties;
    private final PredictionApi predictions;
    private final ErrorMapper errors;

    @Inject
    public PredictionRepository(PropertyApi properties,
                                PredictionApi predictions,
                                ErrorMapper errors) {
        this.properties = properties;
        this.predictions = predictions;
        this.errors = errors;
    }

    public void priceListing(PropertyDtos.CreatePropertyRequest request,
                             @NonNull Result.Listener<PredictionDtos.PredictionResponse> listener) {
        listener.onResult(Result.loading());
        properties.create(request).enqueue(new Callback<PropertyDtos.PropertyResponse>() {
            @Override
            public void onResponse(@NonNull Call<PropertyDtos.PropertyResponse> call,
                                   @NonNull Response<PropertyDtos.PropertyResponse> response) {
                if (!response.isSuccessful() || response.body() == null) {
                    listener.onResult(Result.error(message(response)));
                    return;
                }
                predict(response.body().id, listener);
            }

            @Override
            public void onFailure(@NonNull Call<PropertyDtos.PropertyResponse> call,
                                  @NonNull Throwable t) {
                listener.onResult(Result.error(describe(t)));
            }
        });
    }

    public void predict(String propertyId,
                        @NonNull Result.Listener<PredictionDtos.PredictionResponse> listener) {
        predictions.predict(new PredictionDtos.PredictionRequest(propertyId))
                .enqueue(new Callback<PredictionDtos.PredictionResponse>() {
                    @Override
                    public void onResponse(@NonNull Call<PredictionDtos.PredictionResponse> call,
                                           @NonNull Response<PredictionDtos.PredictionResponse> response) {
                        PredictionDtos.PredictionResponse body = response.body();
                        if (!response.isSuccessful() || body == null) {
                            listener.onResult(Result.error(message(response)));
                            return;
                        }
                        // A reply with no estimate block is a failure, not a
                        // blank screen. The orchestrator is specified never to
                        // return a partial result, so if one arrives, say so.
                        if (body.estimate == null || body.estimate.price == null) {
                            listener.onResult(Result.error(
                                    "The server answered without an estimate. Nothing to show."));
                            return;
                        }
                        // The response carries the prediction id but not the property
                        // id, so stitch back the one we already know. The input screen
                        // needs it to open the result screen.
                        body.propertyId = propertyId;
                        listener.onResult(Result.success(body));
                    }

                    @Override
                    public void onFailure(@NonNull Call<PredictionDtos.PredictionResponse> call,
                                          @NonNull Throwable t) {
                        listener.onResult(Result.error(describe(t)));
                    }
                });
    }

    /**
     * Past estimates, newest first.
     *
     * <p>An empty list is a success, not an error. A new account has no history
     * and the screen has a sentence for that case; turning it into a failure
     * message would tell the user something is broken when nothing is.
     */
    public void history(int limit,
                        @NonNull Result.Listener<List<PredictionDtos.Summary>> listener) {
        listener.onResult(Result.loading());
        predictions.history(limit).enqueue(new Callback<List<PredictionDtos.Summary>>() {
            @Override
            public void onResponse(@NonNull Call<List<PredictionDtos.Summary>> call,
                                   @NonNull Response<List<PredictionDtos.Summary>> response) {
                List<PredictionDtos.Summary> body = response.body();
                if (!response.isSuccessful() || body == null) {
                    listener.onResult(Result.error(message(response)));
                    return;
                }
                listener.onResult(Result.success(body));
            }

            @Override
            public void onFailure(@NonNull Call<List<PredictionDtos.Summary>> call,
                                  @NonNull Throwable t) {
                listener.onResult(Result.error(describe(t)));
            }
        });
    }

    private String message(Response<?> response) {
        return errors.map(response, fallbackFor(response.code()));
    }

    private static String fallbackFor(int code) {
        switch (code) {
            case 401:
                return "Your session expired. Sign in again.";
            case 422:
                return "The listing was rejected. Check the details and try again.";
            case 503:
                return "The pricing service is busy. Try again in a moment.";
            default:
                return "Request failed (" + code + ").";
        }
    }

    private static String describe(@Nullable Throwable t) {
        if (t instanceof IOException) {
            return "No connection to the server.";
        }
        return t == null || t.getMessage() == null ? "Something went wrong." : t.getMessage();
    }
}