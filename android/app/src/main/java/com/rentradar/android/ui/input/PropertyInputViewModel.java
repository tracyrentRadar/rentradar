package com.rentradar.android.ui.input;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.rentradar.android.data.Result;
import com.rentradar.android.data.remote.dto.PredictionDtos;
import com.rentradar.android.data.remote.dto.PropertyDtos;
import com.rentradar.android.data.repository.PredictionRepository;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;

@HiltViewModel
public class PropertyInputViewModel extends ViewModel {

    private final PredictionRepository repository;
    private final MutableLiveData<Result<PredictionDtos.PredictionResponse>> state =
            new MutableLiveData<>();

    @Inject
    public PropertyInputViewModel(PredictionRepository repository) {
        this.repository = repository;
    }

    public LiveData<Result<PredictionDtos.PredictionResponse>> state() {
        return state;
    }

    public void submit(PropertyDtos.CreatePropertyRequest request) {
        repository.priceListing(request, state::setValue);
    }

    /** Called after navigating away, so coming back does not re-fire the old result. */
    public void clear() {
        state.setValue(null);
    }
}