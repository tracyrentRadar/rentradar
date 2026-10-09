package com.rentradar.android.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.rentradar.android.R;
import com.rentradar.android.data.Result;
import com.rentradar.android.data.repository.AuthRepository;
import com.rentradar.android.databinding.FragmentHomeBinding;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Screen 05. The entry point: what the app does, a way in, and what you asked
 * it last.
 *
 * <p>The list is not clickable yet. Opening a stored estimate means rebuilding
 * the result screen from a saved row rather than from a fresh prediction, which
 * is its own piece of work. A row that looks tappable and does nothing is worse
 * than one that plainly does not, so no ripple is wired until it does.
 */
@AndroidEntryPoint
public class HomeFragment extends Fragment {

    @Inject
    AuthRepository auth;

    private FragmentHomeBinding binding;
    private HomeViewModel viewModel;
    private PredictionAdapter adapter;

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

        viewModel = new ViewModelProvider(this).get(HomeViewModel.class);

        String market = auth.tokens().marketId();
        binding.homeStatus.setText(market == null
                ? getString(R.string.home_status_no_market)
                : getString(R.string.home_status_market, market));

        adapter = new PredictionAdapter(null);
        binding.homeRecentList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.homeRecentList.setAdapter(adapter);

        binding.homeCheckCard.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigate(R.id.action_home_to_input));

        binding.homeSignOut.setOnClickListener(v -> {
            auth.signOut();
            NavHostFragment.findNavController(this).navigate(
                    R.id.loginFragment,
                    null,
                    new NavOptions.Builder()
                            .setPopUpTo(R.id.nav_graph, true)
                            .build());
        });

        viewModel.recent().observe(getViewLifecycleOwner(), this::render);
        viewModel.loadOnce();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Coming back from an estimate should show it. Skipped on the very
        // first pass, where loadOnce has already asked.
        if (viewModel.recent().getValue() != null) {
            viewModel.refresh();
        }
    }

    /**
     * Four states, not two. Loading keeps whatever is on screen rather than
     * blanking it, an empty list gets a sentence rather than a void, and a
     * failure says so instead of looking like an empty history.
     */
    private void render(Result<java.util.List<
            com.rentradar.android.data.remote.dto.PredictionDtos.Summary>> result) {

        if (result == null || result.isLoading()) {
            return;
        }

        if (result.status == Result.Status.ERROR) {
            binding.homeRecentList.setVisibility(View.GONE);
            binding.homeRecentEmpty.setVisibility(View.VISIBLE);
            binding.homeRecentEmpty.setText(result.message == null
                    ? getString(R.string.home_recent_empty)
                    : result.message);
            return;
        }

        adapter.submit(result.data, false);

        boolean empty = adapter.isEmpty();
        binding.homeRecentList.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.homeRecentEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.homeRecentEmpty.setText(R.string.home_recent_empty);
    }

    @Override
    public void onDestroyView() {
        binding.homeRecentList.setAdapter(null);
        binding = null;
        super.onDestroyView();
    }
}