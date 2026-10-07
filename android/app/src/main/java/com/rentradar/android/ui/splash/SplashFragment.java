package com.rentradar.android.ui.splash;

import android.content.res.Configuration;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.rentradar.android.BuildConfig;
import com.rentradar.android.R;
import com.rentradar.android.data.local.TokenStore;
import com.rentradar.android.databinding.FragmentSplashBinding;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class SplashFragment extends Fragment {

    private static final long HOLD_MILLIS = 1100L;

    @Inject
    TokenStore tokens;

    private FragmentSplashBinding binding;
    private Runnable route;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSplashBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        paintSystemBars(ContextCompat.getColor(requireContext(), R.color.md_primary), false);

        if (BuildConfig.DEBUG) {
            binding.splashStatus.setVisibility(View.VISIBLE);
            binding.splashStatus.setText(tokens.isSignedIn() ? "session found" : "no session");
        }

        route = () -> {
            if (!isAdded()) {
                return;
            }
            NavHostFragment.findNavController(this).navigate(tokens.isSignedIn()
                    ? R.id.action_splash_to_home
                    : R.id.action_splash_to_login);
        };
        view.postDelayed(route, HOLD_MILLIS);
    }

    private void paintSystemBars(int color, boolean lightIcons) {
        Window window = requireActivity().getWindow();
        window.setStatusBarColor(color);
        window.setNavigationBarColor(color);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(lightIcons);
    }

    private void restoreSystemBars() {
        TypedValue value = new TypedValue();
        requireContext().getTheme().resolveAttribute(
                com.google.android.material.R.attr.colorSurface, value, true);
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        paintSystemBars(value.data, !night);
    }

    @Override
    public void onDestroyView() {
        if (route != null && getView() != null) {
            getView().removeCallbacks(route);
        }
        route = null;
        restoreSystemBars();
        binding = null;
        super.onDestroyView();
    }
}