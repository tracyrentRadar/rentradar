package com.rentradar.android.ui.auth;

import android.os.Bundle;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.rentradar.android.R;
import com.rentradar.android.data.Result;
import com.rentradar.android.databinding.FragmentLoginBinding;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class LoginFragment extends Fragment {

    private FragmentLoginBinding binding;
    private LoginViewModel vm;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentLoginBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        vm = new ViewModelProvider(this).get(LoginViewModel.class);

        binding.loginSubmit.setOnClickListener(v -> vm.submit(
                text(binding.loginEmailInput),
                text(binding.loginPasswordInput)));

        binding.loginCreate.setOnClickListener(v ->
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_login_to_register));

        // No reset endpoint on the backend yet. Say so rather than dead button it.
        binding.loginForgot.setOnClickListener(v ->
                Snackbar.make(binding.getRoot(),
                        R.string.error_reset_unavailable, Snackbar.LENGTH_SHORT).show());

        vm.emailError().observe(getViewLifecycleOwner(), res ->
                binding.loginEmail.setError(res == null ? null : getString(res)));

        vm.passwordError().observe(getViewLifecycleOwner(), res ->
                binding.loginPassword.setError(res == null ? null : getString(res)));

        vm.state().observe(getViewLifecycleOwner(), result -> {
            if (result == null) {
                return;
            }
            setBusy(result.isLoading());

            if (result.status == Result.Status.SUCCESS) {
                vm.consume();
                NavHostFragment.findNavController(this)
                        .navigate(R.id.action_login_to_home);
            } else if (result.status == Result.Status.ERROR) {
                vm.consume();
                Snackbar.make(binding.getRoot(),
                        result.message != null ? result.message : getString(R.string.error_unknown),
                        Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void setBusy(boolean busy) {
        binding.loginProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.loginSubmit.setEnabled(!busy);
        binding.loginCreate.setEnabled(!busy);
        binding.loginForgot.setEnabled(!busy);
        binding.loginEmail.setEnabled(!busy);
        binding.loginPassword.setEnabled(!busy);
    }

    private static String text(TextInputEditText field) {
        Editable e = field.getText();
        return e == null ? "" : e.toString();
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}