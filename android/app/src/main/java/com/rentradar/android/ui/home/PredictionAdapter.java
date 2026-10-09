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

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The list of past estimates, shared by the home screen and the history screen.
 *
 * <p>The row answers "which place was this" before "what did it cost", because
 * a column of bare money with no address is unreadable once there are more than
 * two of them. Asked and fair sit on the same line so the comparison the user
 * actually came for is one glance, not two.
 */
public class PredictionAdapter extends RecyclerView.Adapter<PredictionAdapter.VH> {

    public interface OnClick {
        void onPrediction(PredictionDtos.Summary prediction);
    }

    private final List<PredictionDtos.Summary> items = new ArrayList<>();
    @Nullable private final OnClick listener;

    /**
     * True when these rows came from the local cache rather than the network.
     * Drives the badge on every row rather than a single banner, because a
     * banner scrolls away and the rows do not.
     */
    private boolean fromCache;

    public PredictionAdapter(@Nullable OnClick listener) {
        this.listener = listener;
    }

    public void submit(@Nullable List<PredictionDtos.Summary> rows, boolean fromCache) {
        this.fromCache = fromCache;
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
        PredictionDtos.Summary p = items.get(position);

        h.title.setText(titleOf(h, p));
        h.prices.setText(pricesOf(h, p));
        h.cached.setVisibility(fromCache ? View.VISIBLE : View.GONE);
        paintVerdict(h.verdict, p.verdict);

        h.itemView.setOnClickListener(listener == null ? null : v -> listener.onPrediction(p));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /**
     * Three cases, because the listing behind an estimate can be deleted after
     * the fact. A missing bedroom count is left out rather than printed as
     * zero: "0 bed" is a statement, and nothing supports it.
     */
    private static String titleOf(VH h, PredictionDtos.Summary p) {
        boolean hasBeds = p.bedrooms != null;
        boolean hasLocality = p.locality != null && !p.locality.trim().isEmpty();

        if (hasBeds && hasLocality) {
            return h.itemView.getContext()
                    .getString(R.string.history_row_title, p.bedrooms, p.locality);
        }
        if (hasBeds) {
            return h.itemView.getContext()
                    .getString(R.string.history_row_title_beds_only, p.bedrooms);
        }
        if (hasLocality) {
            return p.locality;
        }
        return h.itemView.getContext().getString(R.string.history_row_title_unknown);
    }

    private static String pricesOf(VH h, PredictionDtos.Summary p) {
        String fair = money(p.fairPrice);
        if (fair.isEmpty()) {
            return "";
        }
        if (p.askedPrice == null) {
            return h.itemView.getContext().getString(R.string.history_row_fair_only, fair);
        }
        return h.itemView.getContext()
                .getString(R.string.history_row_prices, money(p.askedPrice), fair);
    }

    /**
     * No currency symbol. Every row in a market carries the same one, so
     * repeating it on both figures of every row is noise, and the result screen
     * states it in full.
     */
    private static String money(@Nullable Double amount) {
        if (amount == null) {
            return "";
        }
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.getDefault());
        nf.setMaximumFractionDigits(0);
        return nf.format(amount);
    }

    /**
     * Below reads as an alarm rather than a bargain, which is deliberate. An
     * asking price well under the local norm is the single commonest shape of a
     * rental advance scam, and the fraud model agrees with the colour often
     * enough that softening it would be dishonest.
     */
    private static void paintVerdict(Chip chip, @Nullable String verdict) {
        if (verdict == null) {
            chip.setVisibility(View.GONE);
            return;
        }
        chip.setVisibility(View.VISIBLE);
        switch (verdict) {
            case PredictionDtos.VERDICT_BELOW:
                paint(chip, R.string.verdict_below, R.color.md_error, R.color.md_error_container);
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
        final TextView title;
        final TextView prices;
        final TextView cached;
        final Chip verdict;

        VH(@NonNull View v) {
            super(v);
            title = v.findViewById(R.id.item_title);
            prices = v.findViewById(R.id.item_prices);
            cached = v.findViewById(R.id.item_cached);
            verdict = v.findViewById(R.id.item_verdict);
        }
    }
}