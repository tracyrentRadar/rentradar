package com.rentradar.android.ui.chat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a typed sentence into slots, against closed vocabularies.
 *
 * <p>This is not language understanding and does not pretend to be. It is slot
 * filling against lists the model already knows: the localities, property types
 * and amenities from feature_spec.json, plus numbers. Anything outside those
 * lists is left alone for the script to ask about with chips.
 *
 * <p>That boundary is the point. A general parser that guesses would sometimes
 * be confidently wrong, and a wrong slot produces a real estimate for a property
 * nobody described. Here, a word the parser does not know simply does not fill
 * anything, and the user is asked.
 *
 * <p>No Android types, so it is testable as plain Java. The vocabularies arrive
 * through the constructor, read from arrays.xml by the caller.
 */
public final class SlotParser {

    /** Matches the validation on the input form and the market's own bounds. */
    private static final BigDecimal RENT_FLOOR = new BigDecimal("150");
    private static final BigDecimal RENT_CEILING = new BigDecimal("100000");

    private static final String[] NUMBER_WORDS = {
            "zero", "one", "two", "three", "four", "five",
            "six", "seven", "eight", "nine", "ten", "single", "double"
    };

    private static final Map<String, Integer> WORD_NUMBERS = new HashMap<>();

    static {
        for (int i = 0; i <= 10; i++) {
            WORD_NUMBERS.put(NUMBER_WORDS[i], i);
        }
        // Ghanaian English in listings, common enough to be worth handling.
        WORD_NUMBERS.put("single", 1);
        WORD_NUMBERS.put("double", 2);
    }

    /**
     * Built by hand rather than with String.join, which is API 26 and this app
     * runs from 24. Iterating the array rather than the map's key set also keeps
     * the alternation order fixed, and a regex whose branch order changes
     * between runs is not something to leave in a parser.
     */
    private static final String COUNT = buildCountPattern();

    private static String buildCountPattern() {
        StringBuilder sb = new StringBuilder("(\\d{1,2}");
        for (String word : NUMBER_WORDS) {
            sb.append('|').append(word);
        }
        return sb.append(')').toString();
    }

    private static final Pattern BEDROOMS =
            Pattern.compile(COUNT + "\\s*(?:-|\\s)?\\s*(?:bed\\s*rooms?|bedrooms?|beds?|br|b/r)\\b");
    private static final Pattern BATHROOMS =
            Pattern.compile(COUNT + "\\s*(?:-|\\s)?\\s*(?:bath\\s*rooms?|bathrooms?|baths?|showers?)\\b");
    private static final Pattern TOILETS =
            Pattern.compile(COUNT + "\\s*(?:-|\\s)?\\s*(?:toilets?|wc)\\b");

    private static final Pattern SIZE_SQM =
            Pattern.compile("(\\d[\\d,.]*)\\s*(?:sqm|sq\\s*m|square\\s*met(?:re|er)s?|m2|m²)\\b");
    private static final Pattern SIZE_SQFT =
            Pattern.compile("(\\d[\\d,.]*)\\s*(?:sqft|sq\\s*ft|square\\s*feet|square\\s*foot|ft2)\\b");

    /**
     * A number that is clearly money: carried by a currency word, or by asking
     * or rent language. The k suffix is handled because "2k" is how people
     * actually write it.
     */
    private static final Pattern MONEY_MARKED = Pattern.compile(
            "(?:ghs|gh¢|gh\\s*cedis?|cedis?|₵|\\brent\\b|\\basking\\b|\\bask\\b|\\bfor\\b|\\bat\\b)"
                    + "\\s*(\\d[\\d,.]*)\\s*(k\\b)?"
                    + "|(\\d[\\d,.]*)\\s*(k\\b)?\\s*(?:ghs|gh¢|cedis?|₵|per\\s*month|a\\s*month|monthly|/month|pm\\b)");

    private static final double SQFT_TO_SQM = 0.09290304;

    private final List<String> localities;
    private final List<String> propertyTypes;
    private final List<String> amenities;

    public SlotParser(@NonNull List<String> localities,
                      @NonNull List<String> propertyTypes,
                      @NonNull List<String> amenities) {
        this.localities = localities;
        this.propertyTypes = propertyTypes;
        this.amenities = amenities;
    }

    /**
     * Fills whatever it recognises. Never overwrites a slot that already has a
     * value, so a correction the user made by tapping a chip is not undone by a
     * later sentence that mentions the same thing in passing.
     *
     * @return true if it understood at least one thing
     */
    public boolean parseInto(@Nullable String raw, @NonNull IntakeSlots slots) {
        if (raw == null || raw.trim().isEmpty()) {
            return false;
        }
        String text = raw.toLowerCase(Locale.ROOT);
        boolean any = false;

        if (slots.bedrooms == null) {
            Integer n = count(BEDROOMS, text, 1, 10);
            if (n != null) { slots.bedrooms = n; any = true; }
        }
        if (slots.bathrooms == null) {
            Integer n = count(BATHROOMS, text, 1, 10);
            if (n != null) { slots.bathrooms = n; any = true; }
        }
        if (slots.toilets == null) {
            Integer n = count(TOILETS, text, 0, 20);
            if (n != null) { slots.toilets = n; any = true; }
        }
        if (slots.locality == null) {
            String hit = longestMatch(localities, text);
            if (hit != null) { slots.locality = hit; any = true; }
        }
        if (slots.propertyType == null) {
            String hit = longestMatch(propertyTypes, text);
            if (hit != null) { slots.propertyType = hit; any = true; }
        }
        if (!slots.amenitiesAnswered) {
            boolean found = false;
            for (String a : amenities) {
                if (contains(text, a) && !slots.amenities.contains(a)) {
                    slots.amenities.add(a);
                    found = true;
                }
            }
            // Mentioning amenities is not the same as finishing the question,
            // so the script still asks. It arrives with these pre-ticked.
            if (found) { any = true; }
        }
        if (slots.sizeSqm == null) {
            BigDecimal area = area(text);
            if (area != null) { slots.sizeSqm = area; any = true; }
        }
        if (slots.rent == null) {
            BigDecimal money = money(text, slots);
            if (money != null) { slots.rent = money; any = true; }
        }
        return any;
    }

    /** A bare number on its own, for when the script asked a direct question. */
    @Nullable
    public static Integer bareCount(@Nullable String raw, int min, int max) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        Integer word = WORD_NUMBERS.get(t);
        if (word != null) {
            return word >= min && word <= max ? word : null;
        }
        try {
            int n = Integer.parseInt(t.replaceAll("[^0-9]", ""));
            return n >= min && n <= max ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A bare amount, for when the script asked for the rent or the size. */
    @Nullable
    public static BigDecimal bareAmount(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT).replace(",", "");
        boolean thousands = t.endsWith("k");
        if (thousands) {
            t = t.substring(0, t.length() - 1).trim();
        }
        t = t.replaceAll("[^0-9.]", "");
        if (t.isEmpty()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(t);
            return thousands ? v.multiply(new BigDecimal("1000")) : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Integer count(Pattern p, String text, int min, int max) {
        Matcher m = p.matcher(text);
        if (!m.find()) {
            return null;
        }
        String token = m.group(1);
        Integer n = WORD_NUMBERS.get(token);
        if (n == null) {
            try {
                n = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return n >= min && n <= max ? n : null;
    }

    @Nullable
    private BigDecimal area(String text) {
        Matcher m = SIZE_SQM.matcher(text);
        if (m.find()) {
            return bareAmount(m.group(1));
        }
        m = SIZE_SQFT.matcher(text);
        if (m.find()) {
            BigDecimal ft = bareAmount(m.group(1));
            if (ft != null) {
                return ft.multiply(BigDecimal.valueOf(SQFT_TO_SQM))
                        .setScale(1, java.math.RoundingMode.HALF_UP);
            }
        }
        return null;
    }

    /**
     * Money only when something marks it as money. A bare number is left alone,
     * because in "3 bed 2 bath" every number is a count, and guessing one of
     * them is a price is exactly the silent wrong answer this design avoids.
     */
    @Nullable
    private BigDecimal money(String text, IntakeSlots slots) {
        Matcher m = MONEY_MARKED.matcher(text);
        while (m.find()) {
            String digits = m.group(1) != null ? m.group(1) : m.group(3);
            String k = m.group(1) != null ? m.group(2) : m.group(4);
            BigDecimal v = bareAmount(digits);
            if (v == null) {
                continue;
            }
            if (k != null) {
                v = v.multiply(new BigDecimal("1000"));
            }
            if (v.compareTo(RENT_FLOOR) >= 0 && v.compareTo(RENT_CEILING) <= 0) {
                return v;
            }
        }
        return null;
    }

    /**
     * Longest match wins, so "East Legon" is not shadowed by "Legon" when both
     * are in the vocabulary. Matching is on the whole phrase, so a locality
     * whose name is two words still resolves from free text.
     */
    @Nullable
    private static String longestMatch(List<String> vocabulary, String text) {
        String best = null;
        for (String candidate : vocabulary) {
            if (contains(text, candidate)
                    && (best == null || candidate.length() > best.length())) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean contains(String haystack, String needle) {
        if (needle == null || needle.trim().isEmpty()) {
            return false;
        }
        String n = needle.toLowerCase(Locale.ROOT).trim();
        int at = haystack.indexOf(n);
        while (at >= 0) {
            boolean leftOk = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            int end = at + n.length();
            boolean rightOk = end >= haystack.length()
                    || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            at = haystack.indexOf(n, at + 1);
        }
        return false;
    }

    /** Convenience for tests and for the amenity chip list. */
    public List<String> amenityVocabulary() {
        return Arrays.asList(amenities.toArray(new String[0]));
    }
}