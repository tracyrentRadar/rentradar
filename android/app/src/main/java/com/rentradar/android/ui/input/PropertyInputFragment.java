package com.rentradar.android.ui.input;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.rentradar.android.R;
import com.rentradar.android.data.Result;
import com.rentradar.android.data.remote.dto.PredictionDtos;
import com.rentradar.android.data.remote.dto.PropertyDtos;
import com.rentradar.android.databinding.FragmentPropertyInputBinding;
import com.rentradar.android.ui.result.ResultFragment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * The listing form.
 *
 * Every dropdown is filled from arrays.xml, which holds the exact vocabulary in
 * feature_spec.json. A value the model never saw cannot be chosen, so a feature
 * is never silently discarded at serving time.
 *
 * The price bounds match RENT_FLOOR_GHS and RENT_CEILING_GHS in the feature
 * builder. The model never saw a rent outside that range, so the app does not
 * ask it to predict one.
 */
@AndroidEntryPoint
public class PropertyInputFragment extends Fragment {

    // ----------------------------------------------------------------- //
    // CONFIRM THESE TWO against your backend before the first submission.
    // Search the backend for validSources. MARKET_ID must be the Ghana
    // market's id or code, and SOURCE must be one of that market's accepted
    // provenance values, or every submission comes back 422.
    // ----------------------------------------------------------------- //
    private static final String MARKET_ID = "GH";
    private static final String SOURCE = "USER_SUBMITTED";

    private static final String CITY = "Accra";
    private static final String CURRENCY = "GHS";

    private static final BigDecimal RENT_FLOOR = new BigDecimal("300");
    private static final BigDecimal RENT_CEILING = new BigDecimal("300000");

    private FragmentPropertyInputBinding binding;
    private PropertyInputViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPropertyInputBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(PropertyInputViewModel.class);

        fillDropdowns();
        buildAmenityChips();

        binding.submitButton.setOnClickListener(v -> onSubmit());
        viewModel.state().observe(getViewLifecycleOwner(), this::render);
    }

    private void fillDropdowns() {
        items(binding.inputLocality, R.array.localities);
        items(binding.inputType, R.array.property_types);
        items(binding.inputBedrooms, R.array.counts_1_to_10);
        items(binding.inputBathrooms, R.array.counts_1_to_10);
        items(binding.inputToilets, R.array.counts_0_to_10);
        items(binding.inputParking, R.array.counts_0_to_10);
        items(binding.inputSizeUnit, R.array.area_units);

        // Only one unit is ever right for this market, so it is preselected
        // rather than left blank for the user to puzzle over.
        binding.inputSizeUnit.setText("sqm", false);
    }

    private void items(MaterialAutoCompleteTextView field, int arrayRes) {
        field.setSimpleItems(getResources().getStringArray(arrayRes));
    }

    private void buildAmenityChips() {
        binding.amenityGroup.removeAllViews();
        for (String name : getResources().getStringArray(R.array.amenities)) {
            Chip chip = new Chip(requireContext());
            chip.setText(name);
            chip.setCheckable(true);
            chip.setCheckedIconVisible(true);
            binding.amenityGroup.addView(chip);
        }
    }

    private List<String> checkedAmenities() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < binding.amenityGroup.getChildCount(); i++) {
            View v = binding.amenityGroup.getChildAt(i);
            if (v instanceof Chip && ((Chip) v).isChecked()) {
                out.add(((Chip) v).getText().toString());
            }
        }
        return out;
    }

    private void onSubmit() {
        clearErrors();
        binding.formError.setVisibility(View.GONE);

        String locality = text(binding.inputLocality);
        String type = text(binding.inputType);
        String bedrooms = text(binding.inputBedrooms);
        String bathrooms = text(binding.inputBathrooms);
        String toilets = text(binding.inputToilets);
        String parking = text(binding.inputParking);
        String size = text(binding.inputSize);
        String unit = text(binding.inputSizeUnit);
        String price = text(binding.inputPrice);

        boolean ok = true;
        if (locality.isEmpty()) ok = require(binding.layoutLocality);
        if (bedrooms.isEmpty()) ok = require(binding.layoutBedrooms) && ok;
        if (bathrooms.isEmpty()) ok = require(binding.layoutBathrooms) && ok;
        if (size.isEmpty()) ok = require(binding.layoutSize) && ok;

        BigDecimal rent = null;
        if (price.isEmpty()) {
            ok = require(binding.layoutPrice) && ok;
        } else {
            try {
                rent = new BigDecimal(price);
                if (rent.compareTo(RENT_FLOOR) < 0 || rent.compareTo(RENT_CEILING) > 0) {
                    binding.layoutPrice.setError(getString(R.string.input_price_range));
                    ok = false;
                }
            } catch (NumberFormatException e) {
                binding.layoutPrice.setError(getString(R.string.input_price_range));
                ok = false;
            }
        }

        BigDecimal area = null;
        if (!size.isEmpty()) {
            try {
                area = new BigDecimal(size);
                if (area.signum() <= 0) {
                    ok = require(binding.layoutSize) && ok;
                }
            } catch (NumberFormatException e) {
                ok = require(binding.layoutSize) && ok;
            }
        }

        if (!ok) {
            return;
        }

        // The title is not a model feature, so rather than make the user think
        // of one, it is composed from what they already entered.
        String title = text(binding.inputTitle);
        if (title.isEmpty()) {
            title = bedrooms + " bedroom "
                    + (type.isEmpty() ? "property" : type.toLowerCase())
                    + " in " + locality;
        }
        if (title.length() > 140) {
            title = title.substring(0, 140);
        }

        PropertyDtos.CreatePropertyRequest request = new PropertyDtos.CreatePropertyRequest(
                MARKET_ID,
                CITY,
                locality,
                title,
                Integer.parseInt(bedrooms),
                Integer.parseInt(bathrooms),
                toilets.isEmpty() ? null : Integer.parseInt(toilets),
                parking.isEmpty() ? null : Integer.parseInt(parking),
                type.isEmpty() ? null : type,
                area,
                "sqft".equalsIgnoreCase(unit)
                        ? PropertyDtos.UNIT_SQFT
                        : PropertyDtos.UNIT_SQM,
                rent,
                CURRENCY,
                binding.switchFurnished.isChecked(),
                checkedAmenities(),
                SOURCE,
                null);

        viewModel.submit(request);
    }

    private void render(@Nullable Result<PredictionDtos.PredictionResponse> result) {
        if (result == null) {
            return;
        }
        boolean busy = result.isLoading();
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.submitButton.setEnabled(!busy);

        if (result.status == Result.Status.ERROR) {
            binding.formError.setText(result.message);
            binding.formError.setVisibility(View.VISIBLE);
            return;
        }

        if (result.status == Result.Status.SUCCESS && result.data != null) {
            String propertyId = result.data.propertyId;
            if (propertyId == null) {
                binding.formError.setText(R.string.input_no_id);
                binding.formError.setVisibility(View.VISIBLE);
                return;
            }
            double asking = 0d;
            try {
                asking = Double.parseDouble(text(binding.inputPrice));
            } catch (NumberFormatException ignored) {
            }
            Bundle args = ResultFragment.argsOf(propertyId, asking, text(binding.inputLocality));
            viewModel.clear();
            NavHostFragment.findNavController(this)
                    .navigate(R.id.action_input_to_result, args);
        }
    }

    private boolean require(TextInputLayout layout) {
        layout.setError(getString(R.string.input_required));
        return false;
    }

    private void clearErrors() {
        binding.layoutTitle.setError(null);
        binding.layoutLocality.setError(null);
        binding.layoutType.setError(null);
        binding.layoutBedrooms.setError(null);
        binding.layoutBathrooms.setError(null);
        binding.layoutToilets.setError(null);
        binding.layoutParking.setError(null);
        binding.layoutSize.setError(null);
        binding.layoutPrice.setError(null);
    }

    private static String text(@Nullable android.widget.TextView v) {
        return v == null || v.getText() == null ? "" : v.getText().toString().trim();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}