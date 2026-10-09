package com.rentradar.android.ui.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One row in the intake conversation.
 *
 * BOT   a question or a remark from RentRadar, drawn as plain text.
 * USER  something the person typed or chose, drawn in a bubble on the right.
 * CHIPS the tappable answers for the question directly above.
 */
public final class ChatMessage {

    public enum Kind { BOT, USER, CHIPS }

    public final Kind kind;
    public final String text;
    public final List<String> options;
    public final boolean multi;

    /** Which boxes are ticked. Only used while kind is CHIPS and multi is true. */
    public final Set<String> selected = new LinkedHashSet<>();

    /** Answered already. The row stays on screen, dimmed, so the thread reads back. */
    public boolean spent;

    private ChatMessage(Kind kind, String text, List<String> options, boolean multi) {
        this.kind = kind;
        this.text = text;
        this.options = options == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(options));
        this.multi = multi;
    }

    public static ChatMessage bot(String text) {
        return new ChatMessage(Kind.BOT, text, null, false);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Kind.USER, text, null, false);
    }

    /** One tap answers the question. */
    public static ChatMessage chips(List<String> options) {
        return new ChatMessage(Kind.CHIPS, null, options, false);
    }

    /** Tick as many as apply. The last entry closes the question. */
    public static ChatMessage multiChips(List<String> options) {
        return new ChatMessage(Kind.CHIPS, null, options, true);
    }
}