package com.rentradar.android.ui.home;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.rentradar.android.R;
import com.rentradar.android.data.remote.dto.PredictionDtos;
import com.rentradar.android.data.remote.dto.PropertyDtos;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Currency;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The list of past checks on the home screen.
 *
 * Each row carries the interval, not just the point estimate, for the same
 * reason the result screen does. A saved number with no range around it reads
 * as a settled fact, and a month later the user has forgotten how wide it was.
 */
public class PredictionAdapter extends RecyclerView.Adapter<PredictionAdapter.VH> {

    public interface OnClick {
        void onPrediction(PredictionDtos.StoredPrediction prediction);
    }

    private final List<PredictionDtos.StoredPrediction> items = new ArrayList<>();
    @Nullable private final OnClick listener;

    public PredictionAdapter(@Nullable OnClick listener) {
        this.listener = listener;
    }

    public void submit(@Nullable List<PredictionDtos.StoredPrediction> rows) {
        items.clear();
        if (rows != null) {
            items.addAll(rows);
        }
        notifyDataSetChanged();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_prediction, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        PredictionDtos.StoredPrediction p = items.get(position);

        String code = currencyOf(p);
        if (p.ciLower != null && p.ciUpper != null) {
            h.estimate.setText(money(p.ciLower.amount, code)
                    + " " + h.itemView.getContext().getString(R.string.result_range_separator)
                    + " " + money(p.ciUpper.amount, code));
        } else if (p.predictedPrice != null) {
            h.estimate.setText(money(p.predictedPrice.amount, code));
        } else {
            h.estimate.setText("");
        }

        h.when.setText(shortDate(p.createdAt));
        paintVerdict(h.verdict, p.verdict);

        h.itemView.setOnClickListener(listener == null ? null : v -> listener.onPrediction(p));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static String currencyOf(PredictionDtos.StoredPrediction p) {
        if (p.predictedPrice != null && p.predictedPrice.currency != null) {
            return p.predictedPrice.currency;
        }
        if (p.ciLower != null && p.ciLower.currency != null) {
            return p.ciLower.currency;
        }
        return "GHS";
    }

    private static String money(@Nullable BigDecimal amount, String code) {
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

    /**
     * minSdk is 24 and core library desugaring is off, so java.time would
     * compile and then crash on API 24 and 25. Only the date part is needed,
     * so the first ten characters of the ISO timestamp are parsed instead.
     */
    private static String shortDate(@Nullable String iso) {
        if (iso == null || iso.length() < 10) {
            return "";
        }
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = in.parse(iso.substring(0, 10));
            if (d == null) {
                return "";
            }
            return new SimpleDateFormat("d MMMM", Locale.getDefault()).format(d);
        } catch (ParseException e) {
            return "";
        }
    }

    private static void paintVerdict(Chip chip, @Nullable String verdict) {
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

    private static void paint(Chip chip, int label, @ColorRes int text, @ColorRes int background) {
        chip.setText(label);
        chip.setTextColor(ContextCompat.getColor(chip.getContext(), text));
        chip.setChipBackgroundColorResource(background);
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView estimate;
        final TextView when;
        final Chip verdict;

        VH(@NonNull View v) {
            super(v);
            estimate = v.findViewById(R.id.item_estimate);
            when = v.findViewById(R.id.item_when);
            verdict = v.findViewById(R.id.item_verdict);
        }
    }
}