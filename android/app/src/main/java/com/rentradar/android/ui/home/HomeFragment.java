package com.rentradar.android.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import com.rentradar.android.R;
import com.rentradar.android.data.repository.AuthRepository;
import com.rentradar.android.databinding.FragmentHomeBinding;


import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Placeholder. Proves the token survived the round trip and gives a way back
 * out. The real dashboard, screen 05, replaces this.
 */
@AndroidEntryPoint
public class HomeFragment extends Fragment {

    @Inject
    AuthRepository auth;

    private FragmentHomeBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        String market = auth.tokens().marketId();
        binding.homeStatus.setText(market == null
                ? "Signed in. No market on the account."
                : "Signed in. Market " + market + ".");

        binding.homeSignOut.setOnClickListener(v -> {
            auth.signOut();
            NavHostFragment.findNavController(this).navigate(
                    R.id.loginFragment,
                    null,
                    new NavOptions.Builder()
                            .setPopUpTo(R.id.nav_graph, true)
                            .build());
        });

        binding.homeCheckCard.setOnClickListener(v -> {
            androidx.navigation.fragment.NavHostFragment.findNavController(this)
                    .navigate(R.id.action_home_to_input);
        });
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}