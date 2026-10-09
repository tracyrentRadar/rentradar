package com.rentradar.android.ui.chat;

import android.content.Context;

import com.rentradar.android.R;

import java.util.Random;

/** Picks the line under the logo on the empty chat. */
public final class ChatGreetings {

    private static final Random RANDOM = new Random();

    private ChatGreetings() {
    }

    public static String pick(Context context) {
        String[] lines = context.getResources().getStringArray(R.array.chat_greetings);
        if (lines.length == 0) {
            return "";
        }
        return lines[RANDOM.nextInt(lines.length)];
    }
}