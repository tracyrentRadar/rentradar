package com.rentradar.android.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;

import com.rentradar.android.R;
import com.rentradar.android.data.SessionManager;
import com.rentradar.android.databinding.ActivityMainBinding;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

/** The only Activity. Everything else is a Fragment inside the nav host. */
@AndroidEntryPoint
public class MainActivity extends AppCompatActivity {

    @Inject
    SessionManager session;

    private ActivityMainBinding binding;
    private NavController navController;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        NavHostFragment host = (NavHostFragment)
                getSupportFragmentManager().findFragmentById(R.id.nav_host);
        if (host == null) {
            throw new IllegalStateException("nav_host missing from activity_main");
        }
        navController = host.getNavController();

        session.expired().observe(this, expired -> {
            if (Boolean.TRUE.equals(expired)) {
                session.consume();
                navController.navigate(
                        R.id.loginFragment,
                        null,
                        new NavOptions.Builder()
                                .setPopUpTo(R.id.nav_graph, true)
                                .build());
            }
        });
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}