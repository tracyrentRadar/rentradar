package com.rentradar.android.data.remote;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * The backend answers failures with RFC 9457 ProblemDetail, so the useful
 * sentence is in "detail". Validation failures also carry a "fields" object.
 */
@Singleton
public class ErrorMapper {

    private final Gson gson;

    @Inject
    public ErrorMapper(Gson gson) {
        this.gson = gson;
    }

    @NonNull
    public String map(retrofit2.Response<?> response, String fallback) {
        try {
            if (response.errorBody() == null) {
                return fallback;
            }
            String raw = response.errorBody().string();
            if (raw.isEmpty()) {
                return fallback;
            }
            JsonObject problem = gson.fromJson(raw, JsonObject.class);
            if (problem == null) {
                return fallback;
            }

            if (problem.has("fields") && problem.get("fields").isJsonObject()) {
                JsonObject fields = problem.getAsJsonObject("fields");
                for (String key : fields.keySet()) {
                    // Show the first field error. The screen highlights the
                    // field itself, this is the sentence under it.
                    return fields.get(key).getAsString();
                }
            }
            if (problem.has("detail")) {
                return problem.get("detail").getAsString();
            }
            if (problem.has("title")) {
                return problem.get("title").getAsString();
            }
            return fallback;
        } catch (java.io.IOException | JsonSyntaxException | IllegalStateException e) {
            return fallback;
        }
    }
}