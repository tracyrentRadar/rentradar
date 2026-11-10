package com.rentradar.android.ui.chat;

import android.content.Context;
import android.content.res.Resources;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.rentradar.android.R;
import com.rentradar.android.data.Result;
import com.rentradar.android.data.remote.dto.PredictionDtos;
import com.rentradar.android.data.remote.dto.PropertyDtos;
import com.rentradar.android.data.repository.PredictionRepository;
import com.rentradar.android.ui.chat.IntakeSlots.Slot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import dagger.hilt.android.qualifiers.ApplicationContext;

/**
 * Runs the intake conversation.
 *
 * <p>Seven questions, asked one at a time, in the order IntakeSlots.nextMissing
 * returns them. A typed sentence is first run through SlotParser, which may fill
 * several slots at once; whatever it could not fill is asked for with chips.
 * Nothing is sent until the person has seen a summary and tapped "Get the
 * estimate", so a misread is always visible and one tap from fixed.
 *
 * <p>The transcript lives here, not in the fragment, so a rotation keeps the
 * conversation. The fragment only draws rows and forwards taps.
 */
@HiltViewModel
public class ChatViewModel extends ViewModel {

    // Same constants the form uses. The market is resolved server side from
    // the locality; these only have to be accepted by that market.
    private static final String MARKET_ID = "GH";
    private static final String CITY = "Accra";
    private static final String CURRENCY = "GHS";
    private static final String SOURCE = "USER_SUBMITTED";

    // Sanity bounds only. The server applies the real ones and its message is
    // shown verbatim if it rejects the figure.
    private static final BigDecimal RENT_FLOOR = new BigDecimal("100");
    private static final BigDecimal RENT_CEILING = new BigDecimal("500000");

    private static final List<String> BEDROOM_CHIPS = Arrays.asList("1", "2", "3", "4", "5", "6");
    private static final List<String> BATHROOM_CHIPS = Arrays.asList("1", "2", "3", "4", "5");
    private static final List<String> SIZE_CHIPS = Arrays.asList("60", "90", "120", "180", "250");

    private enum Phase { ASKING, CONFIRMING, CHANGING, SUBMITTING }

    private final Resources res;
    private final PredictionRepository repository;
    private final SlotParser parser;
    private final List<String> localities;
    private final List<String> propertyTypes;
    private final List<String> amenities;

    private final List<ChatMessage> transcript = new ArrayList<>();
    private final MutableLiveData<List<ChatMessage>> messages = new MutableLiveData<>();
    private final MutableLiveData<Integer> progress = new MutableLiveData<>(0);
    private final MutableLiveData<Boolean> greetingVisible = new MutableLiveData<>(true);
    private final MutableLiveData<Result<PredictionDtos.PredictionResponse>> submission =
            new MutableLiveData<>();

    private IntakeSlots slots = new IntakeSlots();
    private Phase phase = Phase.ASKING;
    private Slot asking = Slot.NONE;
    @Nullable private ChatMessage openChips;

    @Inject
    public ChatViewModel(@ApplicationContext Context context, PredictionRepository repository) {
        this.res = context.getResources();
        this.repository = repository;
        this.localities = Arrays.asList(res.getStringArray(R.array.localities));
        this.propertyTypes = Arrays.asList(res.getStringArray(R.array.property_types));
        this.amenities = Arrays.asList(res.getStringArray(R.array.amenities));
        this.parser = new SlotParser(localities, propertyTypes, amenities);
        start();
    }

    // ------------------------------------------------------------ observed

    public LiveData<List<ChatMessage>> messages() { return messages; }

    public LiveData<Integer> progress() { return progress; }

    public LiveData<Boolean> greetingVisible() { return greetingVisible; }

    public LiveData<Result<PredictionDtos.PredictionResponse>> submission() { return submission; }

    /** The asking price and locality the result screen wants in its arguments. */
    public double askingPrice() {
        return slots.rent == null ? 0d : slots.rent.doubleValue();
    }

    public String locality() {
        return slots.locality == null ? "" : slots.locality;
    }

    /**
     * The one-line summary the result screen prints under the price, for
     * example "2 bed · 110 m² · East Legon, Accra". Only the parts that were
     * answered appear, so a missing size simply leaves a shorter line.
     */
    public String description() {
        List<String> parts = new ArrayList<>();
        if (slots.bedrooms != null) {
            parts.add(res.getString(R.string.result_desc_beds, slots.bedrooms));
        }
        if (slots.sizeSqm != null) {
            parts.add(res.getString(R.string.result_desc_size, plain(slots.sizeSqm)));
        }
        if (slots.locality != null) {
            parts.add(res.getString(R.string.result_desc_place, slots.locality));
        }
        StringBuilder sb = new StringBuilder();
        String separator = res.getString(R.string.result_desc_separator);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** Called after navigating away, so coming back does not re-fire the old result. */
    public void clearSubmission() {
        submission.setValue(null);
    }

    // ------------------------------------------------------------ actions

    public void restart() {
        start();
    }

    /** The + button: show the choices for the current question again. */
    public void showChoices() {
        if (openChips != null && !openChips.spent) {
            return;
        }
        if (phase == Phase.ASKING) {
            addChipsFor(asking);
        } else if (phase == Phase.CONFIRMING) {
            addConfirmChips();
        } else if (phase == Phase.CHANGING) {
            addChangeChips();
        }
        push();
    }

    /** A single choice chip, or the closing chip of a multi choice. */
    public void onChosen(@NonNull ChatMessage row, @NonNull String label) {
        if (row.spent || phase == Phase.SUBMITTING) {
            return;
        }
        row.spent = true;
        openChips = null;
        greetingVisible.setValue(false);

        switch (phase) {
            case ASKING:
                answerByChip(row, label);
                break;
            case CONFIRMING:
                transcript.add(ChatMessage.user(label));
                if (label.equals(res.getString(R.string.chat_chip_estimate))) {
                    submit();
                } else {
                    phase = Phase.CHANGING;
                    transcript.add(ChatMessage.bot(res.getString(R.string.chat_change_which)));
                    addChangeChips();
                }
                break;
            case CHANGING:
                transcript.add(ChatMessage.user(label));
                Slot slot = slotForLabel(label);
                if (slot == Slot.NONE) {
                    confirm();
                } else {
                    slots.clear(slot);
                    ask(slot, false);
                }
                break;
            default:
                break;
        }
        push();
    }

    /** One box of the amenity list went on or off. The row remembers it. */
    public void onToggled(@NonNull ChatMessage row, @NonNull String label, boolean selected) {
        // Nothing to do here: ChatMessage.selected already holds the state and
        // is read when the closing chip is tapped.
    }

    /** Whatever was typed into the box. */
    public void typed(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty() || phase == Phase.SUBMITTING) {
            return;
        }
        String text = raw.trim();
        greetingVisible.setValue(false);
        closeOpenChips();
        transcript.add(ChatMessage.user(text));

        if (phase == Phase.CONFIRMING || phase == Phase.CHANGING) {
            correctFromText(text);
            push();
            return;
        }

        IntakeSlots before = snapshot(slots);
        boolean understood = parser.parseInto(text, slots);

        // The parser only fills from a sentence that marks what each number is.
        // A bare "3" in reply to "how many bedrooms?" is answered here instead.
        if (!isAnswered(asking)) {
            understood = answerDirectly(asking, text) || understood;
        }

        String ack = describeChanges(before, slots);
        if (!ack.isEmpty()) {
            transcript.add(ChatMessage.bot(res.getString(R.string.chat_ack, ack)));
        }

        if (isAnswered(asking)) {
            askNext();
        } else {
            ask(asking, true);
        }
        push();
    }

    // ------------------------------------------------------------ the script

    private void start() {
        transcript.clear();
        slots = new IntakeSlots();
        phase = Phase.ASKING;
        asking = Slot.NONE;
        openChips = null;
        greetingVisible.setValue(true);
        submission.setValue(null);
        transcript.add(ChatMessage.bot(res.getString(R.string.chat_intro)));
        askNext();
        push();
    }

    private void askNext() {
        Slot next = slots.nextMissing();
        if (next == Slot.NONE) {
            confirm();
        } else {
            ask(next, false);
        }
    }

    private void ask(Slot slot, boolean retry) {
        phase = Phase.ASKING;
        asking = slot;
        transcript.add(ChatMessage.bot(res.getString(questionFor(slot, retry))));
        addChipsFor(slot);
    }

    private int questionFor(Slot slot, boolean retry) {
        switch (slot) {
            case LOCALITY:  return retry ? R.string.chat_q_locality_retry : R.string.chat_q_locality;
            case BEDROOMS:  return retry ? R.string.chat_q_count_retry : R.string.chat_q_bedrooms;
            case BATHROOMS: return retry ? R.string.chat_q_count_retry : R.string.chat_q_bathrooms;
            case TYPE:      return retry ? R.string.chat_q_type_retry : R.string.chat_q_type;
            case AMENITIES: return retry ? R.string.chat_q_amenities_retry : R.string.chat_q_amenities;
            case SIZE:      return retry ? R.string.chat_q_size_retry : R.string.chat_q_size;
            case RENT:      return retry ? R.string.chat_q_rent_retry : R.string.chat_q_rent;
            default:        return R.string.chat_intro;
        }
    }

    private void addChipsFor(Slot slot) {
        ChatMessage row;
        switch (slot) {
            case LOCALITY:
                row = ChatMessage.chips(localities);
                break;
            case BEDROOMS:
                row = ChatMessage.chips(BEDROOM_CHIPS);
                break;
            case BATHROOMS:
                row = ChatMessage.chips(BATHROOM_CHIPS);
                break;
            case TYPE:
                row = ChatMessage.chips(propertyTypes);
                break;
            case AMENITIES: {
                List<String> options = new ArrayList<>(amenities);
                options.add(res.getString(R.string.chat_chip_done));
                row = ChatMessage.multiChips(options);
                // Anything the parser already heard arrives pre ticked.
                row.selected.addAll(slots.amenities);
                break;
            }
            case SIZE: {
                List<String> options = new ArrayList<>(SIZE_CHIPS);
                options.add(res.getString(R.string.chat_chip_not_sure));
                row = ChatMessage.chips(options);
                break;
            }
            default:
                // Rent is typed. There is no sensible short list of prices.
                return;
        }
        transcript.add(row);
        openChips = row;
    }

    private void addConfirmChips() {
        ChatMessage row = ChatMessage.chips(Arrays.asList(
                res.getString(R.string.chat_chip_estimate),
                res.getString(R.string.chat_chip_change)));
        transcript.add(row);
        openChips = row;
    }

    private void addChangeChips() {
        ChatMessage row = ChatMessage.chips(Arrays.asList(
                res.getString(R.string.chat_label_locality),
                res.getString(R.string.chat_label_bedrooms),
                res.getString(R.string.chat_label_bathrooms),
                res.getString(R.string.chat_label_type),
                res.getString(R.string.chat_label_amenities),
                res.getString(R.string.chat_label_size),
                res.getString(R.string.chat_label_rent)));
        transcript.add(row);
        openChips = row;
    }

    private Slot slotForLabel(String label) {
        if (label.equals(res.getString(R.string.chat_label_locality)))  return Slot.LOCALITY;
        if (label.equals(res.getString(R.string.chat_label_bedrooms)))  return Slot.BEDROOMS;
        if (label.equals(res.getString(R.string.chat_label_bathrooms))) return Slot.BATHROOMS;
        if (label.equals(res.getString(R.string.chat_label_type)))      return Slot.TYPE;
        if (label.equals(res.getString(R.string.chat_label_amenities))) return Slot.AMENITIES;
        if (label.equals(res.getString(R.string.chat_label_size)))      return Slot.SIZE;
        if (label.equals(res.getString(R.string.chat_label_rent)))      return Slot.RENT;
        return Slot.NONE;
    }

    private void confirm() {
        phase = Phase.CONFIRMING;
        asking = Slot.NONE;
        transcript.add(ChatMessage.bot(res.getString(R.string.chat_confirm, summary())));
        addConfirmChips();
    }

    // ------------------------------------------------------------ answers

    private void answerByChip(ChatMessage row, String label) {
        transcript.add(ChatMessage.user(label));
        switch (asking) {
            case LOCALITY:
                slots.locality = label;
                break;
            case BEDROOMS:
                slots.bedrooms = SlotParser.bareCount(label, 1, 10);
                break;
            case BATHROOMS:
                slots.bathrooms = SlotParser.bareCount(label, 1, 10);
                break;
            case TYPE:
                slots.propertyType = label;
                break;
            case AMENITIES:
                // The closing chip. Whatever is ticked is the answer, and an
                // empty selection is a real answer too: it has none of these.
                slots.amenities.clear();
                slots.amenities.addAll(row.selected);
                slots.amenitiesAnswered = true;
                break;
            case SIZE:
                if (label.equals(res.getString(R.string.chat_chip_not_sure))) {
                    assumeSize();
                } else {
                    slots.sizeSqm = SlotParser.bareAmount(label);
                }
                break;
            default:
                break;
        }
        askNext();
    }

    /** A direct reply to the question on screen, when the sentence parser found nothing for it. */
    private boolean answerDirectly(Slot slot, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        switch (slot) {
            case LOCALITY: {
                String hit = looseMatch(localities, lower);
                if (hit != null) { slots.locality = hit; return true; }
                return false;
            }
            case BEDROOMS: {
                Integer n = SlotParser.bareCount(text, 1, 10);
                if (n != null) { slots.bedrooms = n; return true; }
                return false;
            }
            case BATHROOMS: {
                Integer n = SlotParser.bareCount(text, 1, 10);
                if (n != null) { slots.bathrooms = n; return true; }
                return false;
            }
            case TYPE: {
                String hit = looseMatch(propertyTypes, lower);
                if (hit != null) { slots.propertyType = hit; return true; }
                return false;
            }
            case AMENITIES: {
                if (saysNone(lower)) {
                    slots.amenities.clear();
                    slots.amenitiesAnswered = true;
                    return true;
                }
                // The parser adds any amenities it heard but never closes the
                // question itself. A typed reply to this question closes it.
                if (!slots.amenities.isEmpty()) {
                    slots.amenitiesAnswered = true;
                    return true;
                }
                return false;
            }
            case SIZE: {
                if (lower.contains("not sure") || lower.contains("dont know")
                        || lower.contains("don't know") || lower.contains("no idea")) {
                    assumeSize();
                    return true;
                }
                BigDecimal v = SlotParser.bareAmount(text);
                if (v != null && v.signum() > 0 && v.compareTo(new BigDecimal("5000")) < 0) {
                    slots.sizeSqm = v;
                    return true;
                }
                return false;
            }
            case RENT: {
                BigDecimal v = SlotParser.bareAmount(text);
                if (v != null && v.compareTo(RENT_FLOOR) >= 0 && v.compareTo(RENT_CEILING) <= 0) {
                    slots.rent = v;
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    /** At the confirm step a sentence is a correction: anything it states wins. */
    private void correctFromText(String text) {
        IntakeSlots fresh = new IntakeSlots();
        boolean understood = parser.parseInto(text, fresh);
        if (!understood) {
            transcript.add(ChatMessage.bot(res.getString(R.string.chat_confirm_retry)));
            addConfirmChips();
            phase = Phase.CONFIRMING;
            return;
        }
        IntakeSlots before = snapshot(slots);
        if (fresh.locality != null)     slots.locality = fresh.locality;
        if (fresh.bedrooms != null)     slots.bedrooms = fresh.bedrooms;
        if (fresh.bathrooms != null)    slots.bathrooms = fresh.bathrooms;
        if (fresh.toilets != null)      slots.toilets = fresh.toilets;
        if (fresh.propertyType != null) slots.propertyType = fresh.propertyType;
        if (fresh.sizeSqm != null)      slots.sizeSqm = fresh.sizeSqm;
        if (fresh.rent != null)         slots.rent = fresh.rent;
        if (!fresh.amenities.isEmpty()) {
            for (String a : fresh.amenities) {
                if (!slots.amenities.contains(a)) {
                    slots.amenities.add(a);
                }
            }
            slots.amenitiesAnswered = true;
        }
        String ack = describeChanges(before, slots);
        if (!ack.isEmpty()) {
            transcript.add(ChatMessage.bot(res.getString(R.string.chat_ack, ack)));
        }
        askNext();
    }

    private void assumeSize() {
        int beds = slots.bedrooms == null ? 2 : slots.bedrooms;
        int sqm = 25 + beds * 35;
        slots.sizeSqm = new BigDecimal(sqm);
        transcript.add(ChatMessage.bot(res.getString(R.string.chat_size_assumed, beds, sqm)));
    }

    // ------------------------------------------------------------ submit

    private void submit() {
        phase = Phase.SUBMITTING;
        transcript.add(ChatMessage.bot(res.getString(R.string.chat_working)));

        String type = slots.propertyType == null ? "property" : slots.propertyType;
        String title = slots.bedrooms + " bedroom " + type.toLowerCase(Locale.ROOT)
                + " in " + slots.locality;
        if (title.length() > 140) {
            title = title.substring(0, 140);
        }

        PropertyDtos.CreatePropertyRequest request = new PropertyDtos.CreatePropertyRequest(
                MARKET_ID,
                CITY,
                slots.locality,
                title,
                slots.bedrooms == null ? 1 : slots.bedrooms,
                slots.bathrooms == null ? 1 : slots.bathrooms,
                slots.toilets,
                null,
                slots.propertyType,
                slots.sizeSqm,
                PropertyDtos.UNIT_SQM,
                slots.rent,
                CURRENCY,
                slots.furnished,
                new ArrayList<>(slots.amenities),
                SOURCE,
                null);

        repository.priceListing(request, result -> {
            submission.setValue(result);
            if (result == null || result.isLoading()) {
                return;
            }
            if (result.status == Result.Status.ERROR) {
                String why = result.message == null ? "" : result.message;
                transcript.add(ChatMessage.bot(res.getString(R.string.chat_failed, why)));
            } else {
                // The fragment is navigating to the result. Leave the thread in
                // a state that makes sense when the person comes back to it.
                transcript.add(ChatMessage.bot(res.getString(R.string.chat_done)));
            }
            phase = Phase.CONFIRMING;
            addConfirmChips();
            push();
        });
    }

    // ------------------------------------------------------------ helpers

    private boolean isAnswered(Slot slot) {
        switch (slot) {
            case LOCALITY:  return slots.locality != null && !slots.locality.trim().isEmpty();
            case BEDROOMS:  return slots.bedrooms != null;
            case BATHROOMS: return slots.bathrooms != null;
            case TYPE:      return slots.propertyType != null && !slots.propertyType.trim().isEmpty();
            case AMENITIES: return slots.amenitiesAnswered;
            case SIZE:      return slots.sizeSqm != null;
            case RENT:      return slots.rent != null;
            default:        return true;
        }
    }

    private void closeOpenChips() {
        if (openChips != null) {
            openChips.spent = true;
            openChips = null;
        }
    }

    private void push() {
        messages.setValue(new ArrayList<>(transcript));
        progress.setValue(slots.answered());
    }

    private static IntakeSlots snapshot(IntakeSlots s) {
        IntakeSlots c = new IntakeSlots();
        c.locality = s.locality;
        c.bedrooms = s.bedrooms;
        c.bathrooms = s.bathrooms;
        c.toilets = s.toilets;
        c.propertyType = s.propertyType;
        c.sizeSqm = s.sizeSqm;
        c.rent = s.rent;
        c.furnished = s.furnished;
        c.amenities.addAll(s.amenities);
        c.amenitiesAnswered = s.amenitiesAnswered;
        return c;
    }

    /** "East Legon, 3 bedrooms, asking GHS 4,500 a month", only the parts that changed. */
    private String describeChanges(IntakeSlots before, IntakeSlots after) {
        List<String> parts = new ArrayList<>();
        if (after.locality != null && !after.locality.equals(before.locality)) {
            parts.add(after.locality);
        }
        if (after.bedrooms != null && !after.bedrooms.equals(before.bedrooms)) {
            parts.add(res.getQuantityString(R.plurals.chat_bedrooms, after.bedrooms, after.bedrooms));
        }
        if (after.bathrooms != null && !after.bathrooms.equals(before.bathrooms)) {
            parts.add(res.getQuantityString(R.plurals.chat_bathrooms, after.bathrooms, after.bathrooms));
        }
        if (after.propertyType != null && !after.propertyType.equals(before.propertyType)) {
            parts.add(after.propertyType);
        }
        if (after.amenities.size() > before.amenities.size()) {
            List<String> added = new ArrayList<>(after.amenities);
            added.removeAll(before.amenities);
            parts.add(join(added));
        }
        if (after.sizeSqm != null && !after.sizeSqm.equals(before.sizeSqm)) {
            parts.add(res.getString(R.string.chat_size_format, plain(after.sizeSqm)));
        }
        if (after.rent != null && !after.rent.equals(before.rent)) {
            parts.add(res.getString(R.string.chat_rent_format, money(after.rent)));
        }
        return join(parts);
    }

    private String summary() {
        StringBuilder sb = new StringBuilder();
        line(sb, R.string.chat_label_locality, slots.locality);
        line(sb, R.string.chat_label_bedrooms, slots.bedrooms == null ? null : String.valueOf(slots.bedrooms));
        line(sb, R.string.chat_label_bathrooms, slots.bathrooms == null ? null : String.valueOf(slots.bathrooms));
        line(sb, R.string.chat_label_type, slots.propertyType);
        line(sb, R.string.chat_label_amenities, slots.amenities.isEmpty()
                ? res.getString(R.string.chat_amenities_none) : join(slots.amenities));
        line(sb, R.string.chat_label_size, slots.sizeSqm == null ? null
                : res.getString(R.string.chat_size_format, plain(slots.sizeSqm)));
        line(sb, R.string.chat_label_rent, slots.rent == null ? null
                : res.getString(R.string.chat_rent_format, money(slots.rent)));
        return sb.toString().trim();
    }

    private void line(StringBuilder sb, int labelRes, @Nullable String value) {
        if (value == null) {
            return;
        }
        sb.append(res.getString(labelRes)).append(": ").append(value).append('\n');
    }

    /**
     * The typed text names a vocabulary entry, or is a decent prefix of one.
     * "east leg" finds East Legon; "a" finds nothing, because a one letter
     * match against half the list is a guess, not an answer.
     */
    @Nullable
    private static String looseMatch(List<String> vocabulary, String lower) {
        String typed = lower.trim();
        String best = null;
        for (String candidate : vocabulary) {
            String c = candidate.toLowerCase(Locale.ROOT);
            boolean hit = typed.equals(c)
                    || typed.contains(c)
                    || (typed.length() >= 4 && c.contains(typed));
            if (hit && (best == null || candidate.length() > best.length())) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean saysNone(String lower) {
        return lower.equals("none") || lower.equals("nothing") || lower.equals("no")
                || lower.startsWith("none ") || lower.startsWith("nothing ")
                || lower.contains("no amenities") || lower.contains("none of");
    }

    /** No String.join below API 26, so by hand. */
    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static String money(BigDecimal v) {
        return NumberFormat.getIntegerInstance(Locale.UK).format(v.setScale(0, RoundingMode.HALF_UP));
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}