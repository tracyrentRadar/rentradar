package com.rentradar.android.ui.chat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.rentradar.android.R;

import java.util.ArrayList;
import java.util.List;

/** Draws the intake thread. It holds no state of its own beyond the rows. */
public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** Taps come back here. The fragment hands them to the view model. */
    public interface OnChip {

        /** A single answer was picked, or the closing chip of a multi answer. */
        void onChosen(ChatMessage message, String label);

        /** One box of a multi answer went on or off. */
        void onToggled(ChatMessage message, String label, boolean selected);
    }

    private static final int TYPE_BOT = 0;
    private static final int TYPE_USER = 1;
    private static final int TYPE_CHIPS = 2;

    private final List<ChatMessage> rows = new ArrayList<>();
    private final OnChip listener;

    public ChatAdapter(OnChip listener) {
        this.listener = listener;
    }

    public void submit(List<ChatMessage> next) {
        rows.clear();
        if (next != null) {
            rows.addAll(next);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @Override
    public int getItemViewType(int position) {
        switch (rows.get(position).kind) {
            case USER:
                return TYPE_USER;
            case CHIPS:
                return TYPE_CHIPS;
            default:
                return TYPE_BOT;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_USER:
                return new TextHolder(
                        inflater.inflate(R.layout.item_chat_user, parent, false), R.id.user_text);
            case TYPE_CHIPS:
                return new ChipsHolder(
                        inflater.inflate(R.layout.item_chat_chips, parent, false));
            default:
                return new TextHolder(
                        inflater.inflate(R.layout.item_chat_bot, parent, false), R.id.bot_text);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage message = rows.get(position);
        if (holder instanceof TextHolder) {
            ((TextHolder) holder).bind(message);
        } else if (holder instanceof ChipsHolder) {
            ((ChipsHolder) holder).bind(message, listener);
        }
    }

    static final class TextHolder extends RecyclerView.ViewHolder {

        private final TextView text;

        TextHolder(@NonNull View itemView, int textId) {
            super(itemView);
            text = itemView.findViewById(textId);
        }

        void bind(ChatMessage message) {
            text.setText(message.text);
        }
    }

    static final class ChipsHolder extends RecyclerView.ViewHolder {

        private final ChipGroup group;

        ChipsHolder(@NonNull View itemView) {
            super(itemView);
            group = (ChipGroup) itemView;
        }

        void bind(final ChatMessage message, final OnChip listener) {
            group.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(group.getContext());
            int lastIndex = message.options.size() - 1;

            for (int i = 0; i < message.options.size(); i++) {
                final String label = message.options.get(i);

                // In a multi answer the final chip closes the question. It is
                // found by position, so renaming it in strings.xml is safe.
                boolean closes = message.multi && i == lastIndex;

                Chip chip = (Chip) inflater.inflate(R.layout.view_chat_chip, group, false);
                chip.setText(label);
                chip.setEnabled(!message.spent);

                if (message.multi && !closes) {
                    chip.setCheckable(true);
                    chip.setChecked(message.selected.contains(label));
                    chip.setOnClickListener(v -> {
                        if (message.spent) {
                            return;
                        }
                        boolean on = ((Chip) v).isChecked();
                        if (on) {
                            message.selected.add(label);
                        } else {
                            message.selected.remove(label);
                        }
                        if (listener != null) {
                            listener.onToggled(message, label, on);
                        }
                    });
                } else {
                    chip.setCheckable(false);
                    chip.setOnClickListener(v -> {
                        if (message.spent) {
                            return;
                        }
                        if (listener != null) {
                            listener.onChosen(message, label);
                        }
                    });
                }

                group.addView(chip);
            }

            // Answered rows stay visible but step back.
            group.setAlpha(message.spent ? 0.5f : 1f);
        }
    }
}