package com.rentradar.android.data;

import androidx.annotation.Nullable;

/** What a ViewModel exposes to a screen: loading, a value, or a message. */
public final class Result<T> {

    public enum Status { LOADING, SUCCESS, ERROR }

    public final Status status;
    @Nullable public final T data;
    @Nullable public final String message;

    private Result(Status status, @Nullable T data, @Nullable String message) {
        this.status = status;
        this.data = data;
        this.message = message;
    }

    public static <T> Result<T> loading() {
        return new Result<>(Status.LOADING, null, null);
    }

    public static <T> Result<T> success(@Nullable T data) {
        return new Result<>(Status.SUCCESS, data, null);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(Status.ERROR, null, message);
    }

    public boolean isLoading() {
        return status == Status.LOADING;
    }

    /** Callback the repository hands results back on. Always on the main thread. */
    public interface Listener<T> {
        void onResult(Result<T> result);
    }
}