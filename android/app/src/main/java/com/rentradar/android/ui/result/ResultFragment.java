package com.rentradar.android.ui.result;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.chip.Chip;
import com.rentradar.android.R;
import com.rentradar.android.data.Result;
import com.rentradar.android.data.remote.dto.PredictionDtos;
import com.rentradar.android.databinding.FragmentResultBinding;

import java.text.NumberFormat;
import java.util.Locale;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * The screen the whole project is judged on, laid out as Figma screen 03.
 * Four things it must get right:
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
    public static final String ARG_DESCRIPTION = "description";

    private FragmentResultBinding binding;
    private ResultViewModel viewModel;
    private String propertyId;
    private String description;

    /** The form still calls this one. It gets a plain "Locality, Accra" line. */
    public static Bundle argsOf(String propertyId, double asking, String locality) {
        return argsOf(propertyId, asking, locality, null);
    }

    /**
     * @param description the one-line summary printed under the price, for
     *                    example "2 bed · 110 m² · East Legon, Accra". May be null.
     */
    public static Bundle argsOf(String propertyId, double asking, String locality,
                                @Nullable String description) {
        Bundle b = new Bundle();
        b.putString(ARG_PROPERTY_ID, propertyId);
        b.putDouble(ARG_ASKING, asking);
        b.putString(ARG_LOCALITY, locality);
        b.putString(ARG_DESCRIPTION, description);
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
        description = args.getString(ARG_DESCRIPTION);
        if (description == null || description.trim().isEmpty()) {
            description = getString(R.string.result_desc_place, locality);
        }

        binding.resultBack.setOnClickListener(v ->
                NavHostFragment.findNavController(this).navigateUp());
        binding.errorRetry.setOnClickListener(v -> viewModel.retry(propertyId));
        binding.rowWhy.setOnClickListener(v -> toggle(binding.whyDetail, binding.whyChevron));
        binding.rowForecast.setOnClickListener(v ->
                toggle(binding.forecastDetail, binding.forecastChevron));
        binding.rowModel.setOnClickListener(v -> toggle(binding.modelDetail, binding.modelChevron));

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.load(propertyId, asking > 0 ? asking : null, locality);
    }

    // ------------------------------------------------------------ render

    private void render(@Nullable Result<PredictionDtos.PredictionResponse> result) {
        if (result == null) {
            return;
        }
        if (result.isLoading()) {
            showLoading();
            return;
        }
        if (result.status == Result.Status.ERROR || result.data == null
                || result.data.estimate == null) {
            String message = result.message == null || result.message.trim().isEmpty()
                    ? getString(R.string.error_unknown)
                    : result.message;
            showError(message);
            return;
        }

        showContent();
        PredictionDtos.PredictionResponse r = result.data;
        String code = r.estimate.currency == null ? "GHS" : r.estimate.currency;

        bindEstimate(r, code);
        bindRisk(r);
        bindOutlook(r, code);
        bindModel(r);

        // Live result, so no stale badge. The offline cache fills this in once
        // the Room layer lands.
        binding.cachedBanner.setVisibility(View.GONE);
    }

    private void showLoading() {
        binding.resultProgress.setVisibility(View.VISIBLE);
        binding.errorPanel.setVisibility(View.GONE);
        binding.resultScroll.setVisibility(View.INVISIBLE);
    }

    private void showError(String message) {
        binding.resultProgress.setVisibility(View.GONE);
        binding.errorPanel.setVisibility(View.VISIBLE);
        binding.errorText.setText(message);
        binding.resultScroll.setVisibility(View.INVISIBLE);
    }

    private void showContent() {
        binding.resultProgress.setVisibility(View.GONE);
        binding.errorPanel.setVisibility(View.GONE);
        binding.resultScroll.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------ estimate

    private void bindEstimate(PredictionDtos.PredictionResponse r, String code) {
        PredictionDtos.Estimate e = r.estimate;
        binding.estimatePrice.setText(number(e.price));
        binding.estimateCurrency.setText(code);
        binding.estimateDescription.setText(description);
        binding.stubNotice.setVisibility(r.servedByStub() ? View.VISIBLE : View.GONE);

        Double asking = viewModel.askingPrice();
        Double lower = e.ciLower;
        Double upper = e.ciUpper;
        boolean hasRange = lower != null && upper != null && upper > lower;

        // No interval means no interval shown. The point estimate does not get
        // promoted into the empty space.
        binding.rangeSection.setVisibility(hasRange ? View.VISIBLE : View.GONE);
        if (hasRange) {
            binding.rangeLowerLabel.setText(number(lower));
            binding.rangeUpperLabel.setText(number(upper));
            if (asking != null) {
                binding.rangeAskingRow.setVisibility(View.VISIBLE);
                binding.rangeAskingLabel.setText(
                        getString(R.string.result_asking_pin, number(asking)));
            } else {
                binding.rangeAskingRow.setVisibility(View.GONE);
            }
            drawRange(lower, upper, e.price, asking);
        }

        bindVerdict(e.verdict, asking);
    }

    private void bindVerdict(@Nullable String verdict, @Nullable Double asking) {
        if (verdict == null || asking == null) {
            binding.verdictRow.setVisibility(View.GONE);
            return;
        }
        binding.verdictRow.setVisibility(View.VISIBLE);
        Chip chip = binding.verdictChip;
        switch (verdict) {
            case PredictionDtos.VERDICT_BELOW:
                paint(chip, R.string.verdict_below, R.color.risk_watch, R.color.risk_watch_container);
                binding.verdictText.setText(R.string.verdict_text_below);
                break;
            case PredictionDtos.VERDICT_ABOVE:
                paint(chip, R.string.verdict_above, R.color.risk_watch, R.color.risk_watch_container);
                binding.verdictText.setText(R.string.verdict_text_above);
                break;
            case PredictionDtos.VERDICT_AT:
            default:
                paint(chip, R.string.verdict_at, R.color.md_on_surface, R.color.md_surface_variant);
                binding.verdictText.setText(R.string.verdict_text_at);
                break;
        }
    }

    // ------------------------------------------------------------ listing risk

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
                paint(chip, R.string.risk_chip_high, R.color.md_error, R.color.md_error_container);
                break;
            case PredictionDtos.RISK_MEDIUM:
                paint(chip, R.string.risk_chip_medium, R.color.risk_watch, R.color.risk_watch_container);
                break;
            case PredictionDtos.RISK_LOW:
            default:
                paint(chip, R.string.risk_chip_low, R.color.risk_ok, R.color.risk_ok_container);
                break;
        }

        binding.riskReason.setText(reasonFor(f.level, f.principalFactor));

        boolean worth = !PredictionDtos.RISK_LOW.equals(f.level);
        binding.riskChecklistHeading.setVisibility(worth ? View.VISIBLE : View.GONE);
        binding.riskChecklist.setVisibility(worth ? View.VISIBLE : View.GONE);
    }

    /**
     * The server names the model channel that stood out most, for example
     * "log_rent" or "amenity_count". That is a fact about the model, not a
     * sentence for a tenant, so it is translated here. A value this build has
     * never heard of gets the generic sentence rather than being printed raw.
     * A low risk always gets the reassuring line, whatever channel was named,
     * because at that level nothing stood out enough to be worth a warning.
     */
    private String reasonFor(@NonNull String level, @Nullable String factor) {
        if (PredictionDtos.RISK_LOW.equals(level)) {
            return getString(R.string.risk_reason_low_full, viewModel.locality());
        }
        return getString(factorText(factor), viewModel.locality());
    }

    @StringRes
    private static int factorText(@Nullable String factor) {
        if (factor == null) {
            return R.string.risk_factor_other;
        }
        switch (factor.trim().toLowerCase(Locale.ROOT)) {
            case "log_rent":
            case "rent":
            case "price":
                return R.string.risk_factor_rent;
            case "bedrooms":
                return R.string.risk_factor_bedrooms;
            case "bathrooms":
                return R.string.risk_factor_bathrooms;
            case "toilets":
                return R.string.risk_factor_toilets;
            case "amenity_count":
            case "amenities":
                return R.string.risk_factor_amenities;
            case "distance":
                return R.string.risk_factor_distance;
            default:
                return R.string.risk_factor_other;
        }
    }

    // ------------------------------------------------------------ detail rows

    private void bindOutlook(PredictionDtos.PredictionResponse r, String code) {
        PredictionDtos.Forecast f = r.forecast;
        if (f == null || f.direction == null) {
            binding.forecastSubtitle.setText(R.string.result_forecast_unavailable);
            binding.forecastSubtitle.setTextColor(color(R.color.md_on_surface_variant));
            binding.forecastChevron.setVisibility(View.GONE);
            binding.forecastDetail.setVisibility(View.GONE);
            binding.rowForecast.setClickable(false);
            return;
        }
        binding.rowForecast.setClickable(true);
        binding.forecastChevron.setVisibility(View.VISIBLE);
        binding.forecastSubtitle.setTextColor(color(R.color.md_primary));

        int shortLine;
        int longLine;
        switch (f.direction) {
            case PredictionDtos.TREND_RISING:
                shortLine = R.string.outlook_rising;
                longLine = R.string.forecast_rising;
                break;
            case PredictionDtos.TREND_FALLING:
                shortLine = R.string.outlook_falling;
                longLine = R.string.forecast_falling;
                break;
            case PredictionDtos.TREND_STABLE:
            default:
                shortLine = R.string.outlook_stable;
                longLine = R.string.forecast_stable;
                break;
        }
        binding.forecastSubtitle.setText(shortLine);

        StringBuilder detail = new StringBuilder(getString(longLine, viewModel.locality()));
        if (f.value != null) {
            int days = f.horizonDays == null || f.horizonDays <= 0 ? 30 : f.horizonDays;
            String currency = f.currency == null ? code : f.currency;
            double spread = f.meanAbsoluteError == null ? 0d : f.meanAbsoluteError;
            if (spread <= 0d && f.lower != null && f.upper != null) {
                spread = (f.upper - f.lower) / 2d;
            }
            detail.append(' ');
            if (spread > 0d) {
                detail.append(getString(R.string.outlook_level,
                        days, currency, number(f.value), number(spread)));
            } else {
                detail.append(getString(R.string.outlook_level_plain,
                        days, currency, number(f.value)));
            }
        }
        binding.forecastDetail.setText(detail.toString());
    }

    private void bindModel(PredictionDtos.PredictionResponse r) {
        binding.detailBasis.setText(getString(R.string.result_basis, viewModel.corpusSize()));

        PredictionDtos.Meta m = r.meta;
        if (m == null) {
            binding.modelSubtitle.setText(R.string.result_model_unavailable);
            binding.modelDetail.setText(R.string.result_model_unavailable);
            return;
        }
        String price = String.valueOf(m.priceModelVersion);
        String fraud = String.valueOf(m.fraudModelVersion);
        binding.modelSubtitle.setText(getString(R.string.result_model_pair, price, fraud));
        binding.modelDetail.setText(getString(R.string.result_model_line,
                price, fraud, m.latencyMs == null ? 0L : m.latencyMs));
    }

    /** Opens or closes one detail row; the chevron turns to point down while open. */
    private void toggle(View detail, View chevron) {
        boolean open = detail.getVisibility() == View.VISIBLE;
        detail.setVisibility(open ? View.GONE : View.VISIBLE);
        chevron.animate().rotation(open ? 0f : 90f).setDuration(150).start();
    }

    // ------------------------------------------------------------ range band

    /**
     * Lays the interval out as a band, the estimate as a notch inside it, and
     * the asking price as a pin that may fall outside it. The two range numbers
     * sit under the band's ends and "asking X" sits under the pin. When the
     * asking price is outside the range the window widens on that side only,
     * so the listing is still on screen instead of clamped to the edge and made
     * to look borderline. The band never shrinks below half the width, so the
     * labels under it always have room.
     */
    private void drawRange(double lower, double upper,
                           @Nullable Double estimate, @Nullable Double asking) {
        final FrameLayout bar = binding.rangeBar;
        bar.post(() -> {
            if (binding == null) {
                return;
            }
            int w = bar.getWidth();
            if (w <= 0) {
                return;
            }
            double range = upper - lower;
            double min = lower;
            double max = upper;
            if (asking != null && asking < lower) {
                min = Math.max(asking - range * 0.08d, lower - range);
            }
            if (asking != null && asking > upper) {
                max = Math.min(asking + range * 0.08d, upper + range);
            }
            double span = max - min;

            int bandStart = px(w, (lower - min) / span);
            int bandEnd = px(w, (upper - min) / span);
            FrameLayout.LayoutParams band =
                    (FrameLayout.LayoutParams) binding.rangeBand.getLayoutParams();
            band.setMarginStart(bandStart);
            band.width = Math.max(dp(6), bandEnd - bandStart);
            binding.rangeBand.setLayoutParams(band);

            if (estimate != null) {
                binding.rangePoint.setVisibility(View.VISIBLE);
                centre(binding.rangePoint, px(w, (estimate - min) / span), w);
            } else {
                binding.rangePoint.setVisibility(View.GONE);
            }

            if (asking != null) {
                double fraction = Math.max(0d, Math.min(1d, (asking - min) / span));
                int pinX = px(w, fraction);
                binding.rangeAsking.setVisibility(View.VISIBLE);
                centre(binding.rangeAsking, pinX, w);
                centre(binding.rangeAskingLabel, pinX, w);
            } else {
                binding.rangeAsking.setVisibility(View.GONE);
            }

            startAt(binding.rangeLowerLabel, bandStart, w);
            endAt(binding.rangeUpperLabel, bandEnd, w);
        });
    }

    private static int px(int width, double fraction) {
        return (int) Math.round(fraction * width);
    }

    /** Centres a view on x, kept inside the bar. */
    private static void centre(View v, int x, int barWidth) {
        int width = widthOf(v);
        setStart(v, Math.max(0, Math.min(x - width / 2, barWidth - width)));
    }

    /** Puts a view's left edge at x, kept inside the bar. */
    private static void startAt(View v, int x, int barWidth) {
        setStart(v, Math.max(0, Math.min(x, barWidth - widthOf(v))));
    }

    /** Puts a view's right edge at x, kept inside the bar. */
    private static void endAt(View v, int x, int barWidth) {
        int width = widthOf(v);
        setStart(v, Math.max(0, Math.min(x - width, barWidth - width)));
    }

    /** A fixed width from the layout, or the measured width of wrap_content text. */
    private static int widthOf(View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null && lp.width > 0) {
            return lp.width;
        }
        v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        return v.getMeasuredWidth();
    }

    private static void setStart(View v, int start) {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
        p.setMarginStart(start);
        v.setLayoutParams(p);
    }

    // ------------------------------------------------------------ helpers

    /** Chips carry their label in capitals, as drawn: AT MARKET, LOW. */
    private void paint(Chip chip, @StringRes int label, @ColorRes int text, @ColorRes int background) {
        chip.setText(getString(label).toUpperCase(Locale.getDefault()));
        chip.setTextColor(color(text));
        chip.setChipBackgroundColorResource(background);
    }

    private int color(@ColorRes int res) {
        return ContextCompat.getColor(requireContext(), res);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** A plain number with thousands separators. The currency is printed once, beside the price. */
    private static String number(@Nullable Double amount) {
        if (amount == null) {
            return "";
        }
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.getDefault());
        nf.setMaximumFractionDigits(0);
        return nf.format(amount);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}