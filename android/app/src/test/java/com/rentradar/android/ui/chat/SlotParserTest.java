package com.rentradar.android.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * The parser is the one piece of this screen that can be wrong without saying
 * so, which is exactly why it is the piece that gets tested.
 *
 * <p>The cases below are the sentences a person actually types, plus the ones
 * that would make a careless parser invent a slot.
 */
public class SlotParserTest {

    private static final List<String> LOCALITIES = Arrays.asList(
            "East Legon", "Legon", "Spintex", "Cantonments", "North Ridge",
            "Airport Residential Area", "Dzorwulu");

    private static final List<String> TYPES = Arrays.asList(
            "Apartment", "Detached Duplex", "Townhouse", "Self Contain");

    private static final List<String> AMENITIES = Arrays.asList(
            "Air Conditioning", "Swimming pool", "Standby generator",
            "Security gate", "Fitted kitchen");

    private SlotParser parser() {
        return new SlotParser(LOCALITIES, TYPES, AMENITIES);
    }

    private IntakeSlots parse(String text) {
        IntakeSlots slots = new IntakeSlots();
        parser().parseInto(text, slots);
        return slots;
    }

    @Test
    public void fillsThreeSlotsFromOneSentence() {
        IntakeSlots s = parse("3 bed in east legon for 2000");

        assertEquals(Integer.valueOf(3), s.bedrooms);
        assertEquals("East Legon", s.locality);
        assertEquals(0, new BigDecimal("2000").compareTo(s.rent));
    }

    @Test
    public void understandsNumberWords() {
        assertEquals(Integer.valueOf(3), parse("three bedroom flat").bedrooms);
        assertEquals(Integer.valueOf(2), parse("two baths").bathrooms);
    }

    @Test
    public void understandsAbbreviations() {
        assertEquals(Integer.valueOf(3), parse("3br apartment").bedrooms);
        assertEquals(Integer.valueOf(2), parse("2 b/r").bedrooms);
    }

    @Test
    public void longestLocalityWins() {
        // "Legon" is also in the vocabulary and must not shadow the longer name.
        assertEquals("East Legon", parse("somewhere in east legon").locality);
    }

    @Test
    public void doesNotMatchALocalityInsideAnotherWord() {
        assertNull(parse("legonsomething").locality);
    }

    @Test
    public void readsKAsThousands() {
        assertEquals(0, new BigDecimal("2500").compareTo(parse("asking 2.5k").rent));
    }

    /**
     * The important negative. Every number here is a count. A parser that
     * grabbed the largest number as the price would file a 2 cedi rent, or
     * worse, a 3000 cedi bedroom count.
     */
    @Test
    public void doesNotInventAPriceFromBareCounts() {
        IntakeSlots s = parse("3 bed 2 bath");

        assertEquals(Integer.valueOf(3), s.bedrooms);
        assertEquals(Integer.valueOf(2), s.bathrooms);
        assertNull(s.rent);
    }

    @Test
    public void rejectsAPriceOutsideTheMarketBounds() {
        assertNull(parse("asking 20").rent);
        assertNull(parse("asking 900000").rent);
    }

    @Test
    public void convertsSquareFeetToSquareMetres() {
        BigDecimal m2 = parse("1000 sq ft").sizeSqm;

        assertEquals(0, new BigDecimal("92.9").compareTo(m2));
    }

    @Test
    public void collectsAmenitiesWithoutClosingTheQuestion() {
        IntakeSlots s = parse("has a swimming pool and standby generator");

        assertTrue(s.amenities.contains("Swimming pool"));
        assertTrue(s.amenities.contains("Standby generator"));
        // Mentioning some is not the same as saying that is all of them.
        assertFalse(s.amenitiesAnswered);
    }

    @Test
    public void neverOverwritesAnAnswerTheUserAlreadyGave() {
        IntakeSlots s = new IntakeSlots();
        s.locality = "Spintex";

        parser().parseInto("actually east legon is nice too", s);

        assertEquals("Spintex", s.locality);
    }

    @Test
    public void reportsWhenItUnderstoodNothing() {
        IntakeSlots s = new IntakeSlots();

        assertFalse(parser().parseInto("hello there", s));
        assertEquals(IntakeSlots.Slot.LOCALITY, s.nextMissing());
    }

    @Test
    public void asksForWhateverIsStillMissing() {
        IntakeSlots s = parse("3 bed in spintex for 1800");

        assertEquals(IntakeSlots.Slot.BATHROOMS, s.nextMissing());
        assertEquals(3, s.answered());
    }
}