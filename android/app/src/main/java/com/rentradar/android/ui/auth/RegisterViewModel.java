package com.rentradar.android.ui.auth;

import android.util.Patterns;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.rentradar.android.R;
import com.rentradar.android.data.Result;
import com.rentradar.android.data.remote.dto.AuthDtos;
import com.rentradar.android.data.repository.AuthRepository;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class RegisterViewModel extends ViewModel {

    /** The design asks for twelve. Keep this and the backend rule in step. */
    static final int MIN_PASSWORD_LENGTH = 12;

    private static final String DEFAULT_LOCALE = "en-GH";

    private final AuthRepository repo;

    private final MutableLiveData<Result<AuthDtos.UserSummary>> state = new MutableLiveData<>();
    private final MutableLiveData<Integer> emailError = new MutableLiveData<>();
    private final MutableLiveData<Integer> passwordError = new MutableLiveData<>();

    @Inject
    public RegisterViewModel(AuthRepository repo) {
        this.repo = repo;
    }

    public LiveData<Result<AuthDtos.UserSummary>> state() {
        return state;
    }

    public LiveData<Integer> emailError() {
        return emailError;
    }

    public LiveData<Integer> passwordError() {
        return passwordError;
    }

    public void submit(String email, String password, String role, String marketId) {
        emailError.setValue(null);
        passwordError.setValue(null);

        String trimmed = email == null ? "" : email.trim();
        boolean ok = true;

        if (trimmed.isEmpty()) {
            emailError.setValue(R.string.error_email_required);
            ok = false;
        } else if (!Patterns.EMAIL_ADDRESS.matcher(trimmed).matches()) {
            emailError.setValue(R.string.error_email_invalid);
            ok = false;
        }

        if (password == null || password.isEmpty()) {
            passwordError.setValue(R.string.error_password_required);
            ok = false;
        } else if (password.length() < MIN_PASSWORD_LENGTH) {
            passwordError.setValue(R.string.error_password_short);
            ok = false;
        }

        if (!ok) {
            return;
        }

        state.setValue(Result.loading());
        repo.register(trimmed, password, role, marketId, DEFAULT_LOCALE, state::setValue);
    }

    /** Clears the terminal state so a rotation does not replay a navigation. */
    public void consume() {
        state.setValue(null);
    }
}