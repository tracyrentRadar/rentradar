package com.rentradar.android.data;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Raised when the server refuses our refresh token, which on this backend also
 * means every session for the account was revoked.
 */
@Singleton
public class SessionManager {

    private final MutableLiveData<Boolean> expired = new MutableLiveData<>(false);

    @Inject
    public SessionManager() {
    }

    public LiveData<Boolean> expired() {
        return expired;
    }

    public void notifyExpired() {
        expired.postValue(true);
    }

    public void consume() {
        expired.postValue(false);
    }
}