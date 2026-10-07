package com.rentradar.android.ui.auth;

import android.os.Bundle;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.snackbar.Snackbar;
import com.rentradar.android.R;
import com.rentradar.android.data.Result;
import com.rentradar.android.databinding.FragmentRegisterBinding;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class RegisterFragment extends Fragment {

    // Must match the UserRole enum on the backend.
    private static final String ROLE_TENANT = "TENANT";
    private static final String ROLE_LANDLORD = "LANDLORD";

    private FragmentRegisterBinding binding;
    private RegisterViewModel vm;
    private String[] marketIds;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentRegisterBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        vm = new ViewModelProvider(this).get(RegisterViewModel.class);

        binding.registerToolbar.setNavigationOnClickListener(v ->
                NavHostFragment.findNavController(this).popBackStack());

        String[] labels = getResources().getStringArray(R.array.rr_market_labels);
        marketIds = getResources().getStringArray(R.array.rr_market_ids);
        binding.registerMarketInput.setSimpleItems(labels);
        binding.registerMarketInput.setText(labels[0], false);

        binding.registerRoleGroup.check(R.id.register_role_tenant);

        binding.registerSubmit.setOnClickListener(v -> vm.submit(
                text(binding.registerEmailInput),
                text(binding.registerPasswordInput),
                selectedRole(),
                selectedMarketId()));

        vm.emailError().observe(getViewLifecycleOwner(), res ->
                binding.registerEmail.setError(res == null ? null : getString(res)));

        vm.passwordError().observe(getViewLifecycleOwner(), res ->
                binding.registerPassword.setError(res == null ? null : getString(res)));

        vm.state().observe(getViewLifecycleOwner(), result -> {
            if (result == null) {
                return;
            }
            setBusy(result.isLoading());

            if (result.status == Result.Status.SUCCESS) {
                vm.consume();
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_register_to_home);
            } else if (result.status == Result.Status.ERROR) {
                vm.consume();
                Snackbar.make(binding.getRoot(),
                        result.message != null ? result.message : getString(R.string.error_unknown),
                        Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private String selectedRole() {
        return binding.registerRoleGroup.getCheckedButtonId() == R.id.register_role_landlord
                ? ROLE_LANDLORD
                : ROLE_TENANT;
    }

    /**
     * The dropdown shows a human label, the backend wants the market id. Keep
     * the two arrays index for index rather than parsing the label.
     */
    private String selectedMarketId() {
        String[] labels = getResources().getStringArray(R.array.rr_market_labels);
        String shown = text(binding.registerMarketInput);
        for (int i = 0; i < labels.length && i < marketIds.length; i++) {
            if (labels[i].equals(shown)) {
                return marketIds[i];
            }
        }
        return marketIds[0];
    }

    private void setBusy(boolean busy) {
        binding.registerProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.registerSubmit.setEnabled(!busy);
        binding.registerEmail.setEnabled(!busy);
        binding.registerPassword.setEnabled(!busy);
        binding.registerMarket.setEnabled(!busy);
        binding.registerRoleGroup.setEnabled(!busy);
        binding.registerRoleTenant.setEnabled(!busy);
        binding.registerRoleLandlord.setEnabled(!busy);
    }

    private static String text(EditText field) {
        Editable e = field.getText();
        return e == null ? "" : e.toString();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}