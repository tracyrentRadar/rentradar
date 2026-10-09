package com.rentradar.android.ui.chat;

import androidx.annotation.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * What the conversation has collected so far.
 *
 * <p>Deliberately mutable and deliberately dumb. The parser fills whatever it
 * recognises, in any order, from any sentence; the script then asks only for
 * what is still empty. That is the whole trick behind "type it however you
 * like": there is no fixed sequence, only a set of holes and two ways to fill
 * them.
 *
 * <p>Every field is nullable because null means "not answered yet", which the
 * script needs to distinguish from an answer of zero. amenitiesAnswered exists
 * because an empty amenity list is a real answer, "it has none", and is not the
 * same as never having been asked.
 */
public final class IntakeSlots {

    /** The order the script asks in, when nothing has been filled by typing. */
    public enum Slot {
        LOCALITY, BEDROOMS, BATHROOMS, TYPE, AMENITIES, SIZE, RENT, NONE
    }

    @Nullable public String locality;
    @Nullable public Integer bedrooms;
    @Nullable public Integer bathrooms;
    @Nullable public Integer toilets;
    @Nullable public String propertyType;
    @Nullable public BigDecimal sizeSqm;
    @Nullable public BigDecimal rent;
    @Nullable public Boolean furnished;

    public final List<String> amenities = new ArrayList<>();
    public boolean amenitiesAnswered;

    /**
     * The first thing still missing, in asking order.
     *
     * <p>Only the five the API insists on, plus type and amenities because they
     * are the two that most move the estimate. Toilets, parking and furnishing
     * are never asked: they are optional to the model and unstated is itself a
     * signal it was trained on, so pestering for them would trade a real signal
     * for a guess.
     */
    public Slot nextMissing() {
        if (isBlank(locality)) return Slot.LOCALITY;
        if (bedrooms == null) return Slot.BEDROOMS;
        if (bathrooms == null) return Slot.BATHROOMS;
        if (isBlank(propertyType)) return Slot.TYPE;
        if (!amenitiesAnswered) return Slot.AMENITIES;
        if (sizeSqm == null) return Slot.SIZE;
        if (rent == null) return Slot.RENT;
        return Slot.NONE;
    }

    public boolean complete() {
        return nextMissing() == Slot.NONE;
    }

    /** How many of the seven are filled, for the "2 of 7" progress line. */
    public int answered() {
        int n = 0;
        if (!isBlank(locality)) n++;
        if (bedrooms != null) n++;
        if (bathrooms != null) n++;
        if (!isBlank(propertyType)) n++;
        if (amenitiesAnswered) n++;
        if (sizeSqm != null) n++;
        if (rent != null) n++;
        return n;
    }

    public static final int TOTAL = 7;

    public void clear(Slot slot) {
        switch (slot) {
            case LOCALITY:  locality = null; break;
            case BEDROOMS:  bedrooms = null; break;
            case BATHROOMS: bathrooms = null; break;
            case TYPE:      propertyType = null; break;
            case AMENITIES: amenities.clear(); amenitiesAnswered = false; break;
            case SIZE:      sizeSqm = null; break;
            case RENT:      rent = null; break;
            default: break;
        }
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }
}