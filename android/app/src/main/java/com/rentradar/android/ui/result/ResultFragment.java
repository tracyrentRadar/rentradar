package com.rentradar.android.ui.result;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.chip.Chip;
import com.rentradar.android.R;
import com.rentradar.android.data.Result;
import com.rentradar.android.data.remote.dto.PredictionDtos;
import com.rentradar.android.databinding.FragmentResultBinding;

import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * The screen the whole project is judged on. Four things it must get right:
 *
 *   the interval is as prominent as the point estimate, so nobody reads a
 *   single number as a fact
 *   nothing here accuses a person, only describes a price
 *   an old figure is labelled with its age and why it was not rechecked
 *   the method is reachable, and a placeholder model says so plainly
 *
 * Safe Args is not on this project, so arguments arrive in a plain Bundle.
 */
@AndroidEntryPoint
public class ResultFragment extends Fragment {

    public static final String ARG_PROPERTY_ID = "propertyId";
    public static final String ARG_ASKING = "asking";
    public static final String ARG_LOCALITY = "locality";

    private FragmentResultBinding binding;
    private ResultViewModel viewModel;
    private String propertyId;

    public static Bundle argsOf(String propertyId, double asking, String locality) {
        Bundle b = new Bundle();
        b.putString(ARG_PROPERTY_ID, propertyId);
        b.putDouble(ARG_ASKING, asking);
        b.putString(ARG_LOCALITY, locality);
        return b;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentResultBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(ResultViewModel.class);

        Bundle args = requireArguments();
        propertyId = args.getString(ARG_PROPERTY_ID);
        double asking = args.getDouble(ARG_ASKING, 0d);
        String locality = args.getString(ARG_LOCALITY, "this area");

        binding.detailToggle.setOnClickListener(v -> {
            boolean open = binding.detailPanel.getVisibility() == View.VISIBLE;
            binding.detailPanel.setVisibility(open ? View.GONE : View.VISIBLE);
            binding.detailToggle.setText(open
                    ? R.string.result_how_heading
                    : R.string.result_how_heading);
        });

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.load(propertyId, asking > 0 ? asking : null, locality);
    }

    private void render(Result<PredictionDtos.PredictionResponse> result) {
        if (result == null) {
            return;
        }
        if (result.isLoading()) {
            binding.resultScroll.setVisibility(View.INVISIBLE);
            return;
        }
        if (result.status == Result.Status.ERROR || result.data == null) {
            binding.resultScroll.setVisibility(View.VISIBLE);
            binding.cachedBanner.setVisibility(View.VISIBLE);
            binding.cachedText.setText(result.message);
            return;
        }

        binding.resultScroll.setVisibility(View.VISIBLE);
        PredictionDtos.PredictionResponse r = result.data;
        String code = r.estimate.currency == null ? "GHS" : r.estimate.currency;

        bindEstimate(r, code);
        bindRisk(r);
        bindForecast(r);
        bindDetail(r);

        // Live result, so no stale badge. The offline cache fills this in once
        // the Room layer lands; until then it carries the error text above and
        // is otherwise hidden, rather than showing an age nobody measured.
        if (result.status == Result.Status.SUCCESS) {
            binding.cachedBanner.setVisibility(View.GONE);
        }
    }

    private void bindEstimate(PredictionDtos.PredictionResponse r, String code) {
        PredictionDtos.Estimate e = r.estimate;
        Double lower = e.ciLower;
        Double upper = e.ciUpper;

        if (lower != null && upper != null) {
            binding.estimateLower.setText(money(lower, code));
            binding.estimateUpper.setText(money(upper, code));
            binding.estimateIntervalLabel.setText(
                    getString(R.string.result_interval_label, money(e.price, code)));
        } else {
            // No interval means no interval shown. The point estimate does not
            // get promoted into the empty space.
            binding.estimateLower.setText(money(e.price, code));
            binding.estimateUpper.setText("");
            binding.estimateIntervalLabel.setText("");
        }

        Double asking = viewModel.askingPrice();
        if (asking != null) {
            binding.askingLabel.setText(getString(R.string.result_asking, money(asking, code)));
            binding.askingLabel.setVisibility(View.VISIBLE);
        } else {
            binding.askingLabel.setVisibility(View.GONE);
        }

        bindVerdict(e.verdict);
        drawRange(lower, upper, e.price, asking);
    }

    private void bindVerdict(@Nullable String verdict) {
        Chip chip = binding.verdictChip;
        if (verdict == null) {
            chip.setVisibility(View.GONE);
            return;
        }
        chip.setVisibility(View.VISIBLE);
        switch (verdict) {
            case PredictionDtos.VERDICT_BELOW:
                paint(chip, R.string.verdict_below, R.color.risk_watch, R.color.risk_watch_container);
                break;
            case PredictionDtos.VERDICT_ABOVE:
                paint(chip, R.string.verdict_above, R.color.risk_watch, R.color.risk_watch_container);
                break;
            case PredictionDtos.VERDICT_AT:
            default:
                paint(chip, R.string.verdict_at, R.color.risk_ok, R.color.risk_ok_container);
                break;
        }
    }

    private void bindRisk(PredictionDtos.PredictionResponse r) {
        PredictionDtos.Fraud f = r.fraud;
        if (f == null || f.level == null) {
            binding.riskCard.setVisibility(View.GONE);
            return;
        }
        binding.riskCard.setVisibility(View.VISIBLE);
        Chip chip = binding.riskChip;
        switch (f.level) {
            case PredictionDtos.RISK_HIGH:
                paint(chip, R.string.risk_high, R.color.risk_watch, R.color.risk_watch_container);
                break;
            case PredictionDtos.RISK_MEDIUM:
                paint(chip, R.string.risk_medium, R.color.risk_watch, R.color.risk_watch_container);
                break;
            case PredictionDtos.RISK_LOW:
            default:
                paint(chip, R.string.risk_low, R.color.risk_ok, R.color.risk_ok_container);
                break;
        }

        // The server's own sentence, which describes the price. Showing it
        // rather than composing one here keeps a single wording of the
        // explanation across the API, the logs and the screen.
        if (f.principalFactor != null && !f.principalFactor.trim().isEmpty()) {
            binding.riskReason.setText(f.principalFactor);
        } else {
            binding.riskReason.setText(
                    getString(R.string.risk_reason_low, viewModel.locality()));
        }

        boolean worth = !PredictionDtos.RISK_LOW.equals(f.level);
        binding.riskChecklistHeading.setVisibility(worth ? View.VISIBLE : View.GONE);
        binding.riskChecklist.setVisibility(worth ? View.VISIBLE : View.GONE);
    }

    private void bindForecast(PredictionDtos.PredictionResponse r) {
        PredictionDtos.Forecast f = r.forecast;
        if (f == null || f.direction == null) {
            binding.forecastCard.setVisibility(View.GONE);
            return;
        }
        binding.forecastCard.setVisibility(View.VISIBLE);
        int line;
        switch (f.direction) {
            case "RISING":  line = R.string.forecast_rising;  break;
            case "FALLING": line = R.string.forecast_falling; break;
            default:        line = R.string.forecast_stable;  break;
        }
        binding.forecastSummary.setText(getString(line, viewModel.locality()));
        binding.forecastDetailButton.setVisibility(
                f.series == null || f.series.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void bindDetail(PredictionDtos.PredictionResponse r) {
        binding.detailBasis.setText(getString(R.string.result_basis, viewModel.corpusSize()));
        PredictionDtos.Meta m = r.meta;
        if (m != null) {
            binding.detailModel.setText(getString(R.string.result_model_line,
                    String.valueOf(m.priceModelVersion),
                    String.valueOf(m.fraudModelVersion),
                    m.latencyMs == null ? 0L : m.latencyMs));
        }
        binding.detailStubWarning.setVisibility(r.servedByStub() ? View.VISIBLE : View.GONE);
    }

    /**
     * Lays the interval out as a band, the estimate as a notch inside it, and
     * the asking price as a pin that may fall outside it. The window is widened
     * to include the asking price, so a listing far above the range is still on
     * screen instead of clamped to the edge and made to look borderline.
     */
    private void drawRange(@Nullable Double lower, @Nullable Double upper,
                           @Nullable Double estimate, @Nullable Double asking) {
        if (lower == null || upper == null || upper <= lower) {
            binding.rangeBar.setVisibility(View.GONE);
            return;
        }
        binding.rangeBar.setVisibility(View.VISIBLE);
        final FrameLayout bar = binding.rangeBar;
        bar.post(() -> {
            int w = bar.getWidth();
            if (w <= 0) {
                return;
            }
            double min = lower, max = upper;
            if (asking != null) {
                min = Math.min(min, asking);
                max = Math.max(max, asking);
            }
            double pad = (max - min) * 0.12d;
            min -= pad;
            max += pad;
            double span = max - min;
            if (span <= 0) {
                return;
            }

            FrameLayout.LayoutParams band =
                    (FrameLayout.LayoutParams) binding.rangeBand.getLayoutParams();
            band.setMarginStart((int) ((lower - min) / span * w));
            band.width = Math.max(dp(6), (int) ((upper - lower) / span * w));
            binding.rangeBand.setLayoutParams(band);

            if (estimate != null) {
                place(binding.rangePoint, (estimate - min) / span, w);
            } else {
                binding.rangePoint.setVisibility(View.GONE);
            }

            if (asking != null) {
                binding.rangeAsking.setVisibility(View.VISIBLE);
                place(binding.rangeAsking, (asking - min) / span, w);
            } else {
                binding.rangeAsking.setVisibility(View.GONE);
            }
        });
    }

    private void place(View v, double fraction, int barWidth) {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
        int half = v.getLayoutParams().width / 2;
        int x = (int) (fraction * barWidth) - half;
        p.setMarginStart(Math.max(0, Math.min(x, barWidth - half * 2)));
        v.setLayoutParams(p);
    }

    private void paint(Chip chip, int label, @ColorRes int text, @ColorRes int background) {
        chip.setText(label);
        chip.setTextColor(ContextCompat.getColor(requireContext(), text));
        chip.setChipBackgroundColorResource(background);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String money(@Nullable Double amount, String code) {
        if (amount == null) {
            return "";
        }
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.getDefault());
        nf.setMaximumFractionDigits(0);
        String symbol;
        try {
            symbol = Currency.getInstance(code).getSymbol();
        } catch (IllegalArgumentException ignored) {
            symbol = code;
        }
        return symbol + " " + nf.format(amount);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}