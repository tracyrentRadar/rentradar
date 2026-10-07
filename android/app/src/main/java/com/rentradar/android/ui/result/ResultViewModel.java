package com.rentradar.android.ui.result;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.rentradar.android.data.Result;
import com.rentradar.android.data.remote.dto.PredictionDtos;
import com.rentradar.android.data.repository.PredictionRepository;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

/**
 * Holds the prediction across a rotation, so turning the phone does not ask
 * the server for a second estimate of the same listing.
 */
@HiltViewModel
public class ResultViewModel extends ViewModel {

    private final PredictionRepository repository;
    private final MutableLiveData<Result<PredictionDtos.PredictionResponse>> state =
            new MutableLiveData<>();

    /** What the user typed, kept so the screen can show "Asking X" beside the estimate. */
    private Double askingPrice;
    private String locality;

    /**
     * The size of the corpus the model was trained on. Hardcoded for now, which
     * is the same fault you called out on the vocabulary: it goes stale the
     * moment the model is retrained. It belongs in the meta block of the
     * prediction response, alongside the model versions.
     */
    private final String corpusSize = "2,529";

    @Inject
    public ResultViewModel(PredictionRepository repository) {
        this.repository = repository;
    }

    public LiveData<Result<PredictionDtos.PredictionResponse>> state() {
        return state;
    }

    public Double askingPrice() {
        return askingPrice;
    }

    public String locality() {
        return locality;
    }

    public String corpusSize() {
        return corpusSize;
    }

    public void load(String propertyId, Double asking, String locality) {
        this.askingPrice = asking;
        this.locality = locality;
        if (state.getValue() != null && !state.getValue().isLoading()) {
            return; // survive a rotation without asking the server twice
        }
        repository.predict(propertyId, state::setValue);
    }

    public void retry(String propertyId) {
        state.setValue(Result.loading());
        repository.predict(propertyId, state::setValue);
    }
}