package com.rentradar.android.ui.home;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.rentradar.android.data.Result;
import com.rentradar.android.data.remote.dto.PredictionDtos;
import com.rentradar.android.data.repository.PredictionRepository;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * Holds the recent estimates across a rotation so turning the phone does not
 * re-ask the server for a list that has not changed.
 */
@HiltViewModel
public class HomeViewModel extends ViewModel {

    /**
     * Home shows three. The server takes this as a query parameter rather than
     * the app trimming a full history, so a user with two hundred past
     * estimates still gets a small response on a slow connection.
     */
    private static final int RECENT = 3;

    private final PredictionRepository repository;
    private final MutableLiveData<Result<List<PredictionDtos.Summary>>> recent =
            new MutableLiveData<>();

    @Inject
    public HomeViewModel(PredictionRepository repository) {
        this.repository = repository;
    }

    public LiveData<Result<List<PredictionDtos.Summary>>> recent() {
        return recent;
    }

    /** First load only. A rotation finds a value already here and leaves it. */
    public void loadOnce() {
        if (recent.getValue() != null) {
            return;
        }
        repository.history(RECENT, recent::setValue);
    }

    /**
     * Called when the screen comes back to the front. Coming back from the
     * result screen means a new estimate exists, and a list that does not show
     * it looks broken.
     */
    public void refresh() {
        repository.history(RECENT, recent::setValue);
    }
}