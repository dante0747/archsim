package io.archsim.engine.parity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.catalog.ComponentFacts;
import io.archsim.model.catalog.Preset;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.parity.JsonDiff;

/**
 * Where a third-party API's cost line departs from the frozen engine's because of
 * ADR-0081, and how it is compared all the same. The frozen engine priced an API at its
 * written price, labelled set, or at its default, 0; the Java prices an API without a
 * price at its technology preset's price, or not at all, and labels a written price that
 * is a preset's as the preset's.
 * <ul>
 * <li>A line Python priced at 0 that the Java prices at its technology preset's price
 * (its calls as many as Python's, as the golden files compare numbers) or not at all, or
 * a line whose written price is its technology preset's, which the Java labels
 * {@code preset} where Python said {@code set}, must say what Python's says (its rate at
 * the base load, or the basis listed for the case); it is then Python's line, and the
 * month and the cost per million requests are priced back by what it changed.</li>
 * <li>The cost then lists no part as not priced, once its list is shown to be exactly the
 * departing lines the Java did not price; says whether a part has a price as Python's
 * does, once the Java's is shown to differ only for want of any other priced line; and
 * has Python's notes, once the Java's, but for its notes on API prices, are shown to be
 * Python's in Python's order, give or take the notes a price of 0 decides, each on one
 * side only.</li>
 * </ul>
 */
final class ApiCostDepartures {

	private static final String API = "external_api";

	private static final String UNIT_PRICE = "unitPrice";

	private static final String SOURCE = "priceSource";

	private static final String BASIS = "basis";

	private static final String MONTHLY = "monthly";

	private static final String ITEMS = "items";

	private static final String NOTES = "notes";

	private static final String PRESET = "preset";

	/** The Java's notes on an API's price: a preset's, or none (ADR-0081). */
	private static final List<Pattern> API_NOTES = List.of(
			Pattern.compile("Not priced: .+ \\(no price per million calls set, and no preset price for its "
					+ "technology\\): the month leaves it out\\."),
			Pattern.compile(".+ is priced at the .+ preset's illustrative \\$[0-9.]+ per million calls: set your "
					+ "provider's price on the part\\."));

	/**
	 * The notes a price of 0 decides: whether any part is priced, per call or per
	 * request.
	 */
	private static final List<String> PRICE_NOTES = List.of("No component has a price yet",
			"Third-party APIs are priced per call", "Per-request prices and cost per request");

	private static final List<Preset> PRESETS = new ComponentFacts(StandardComponents.registry()).type(API)
		.orElseThrow()
		.presets();

	private ApiCostDepartures() {
	}

	/**
	 * Whether an API's line departs so.
	 * @param javaLine the Java's line
	 * @param pythonLine Python's line
	 * @return whether Python priced it at 0 and the Java at a preset's price or not at
	 * all, or the Java labels a preset's price that Python labelled set
	 */
	static boolean departs(JsonNode javaLine, JsonNode pythonLine) {
		if (!API.equals(javaLine.path("type").stringValue(""))) {
			return false;
		}
		JsonNode source = javaLine.path(SOURCE);
		boolean presetPrice = PRESET.equals(source.stringValue("")) && isPresetPrice(javaLine);
		boolean pricedAtZero = isZero(pythonLine.path(UNIT_PRICE)) && (source.isNull() || presetPrice);
		boolean relabelled = presetPrice && javaLine.path(UNIT_PRICE).equals(pythonLine.path(UNIT_PRICE));
		return pricedAtZero || relabelled;
	}

	/**
	 * Puts Python's line in the Java's place, once it says what is expected.
	 * @param javaLine the Java's line, changed in place
	 * @param pythonLine Python's line
	 * @param listed what the Java's line must say when its rate at the base load departs
	 * too; {@code null} for Python's basis
	 * @return what the line's month changed by
	 * @throws AssertionError when the Java's line says something else, or bills other
	 * calls
	 */
	static double putLine(ObjectNode javaLine, JsonNode pythonLine, @Nullable String listed) {
		String id = javaLine.path("id").stringValue("");
		String expected = (listed != null) ? listed : pythonLine.path(BASIS).stringValue("");
		String basis = javaLine.path(BASIS).stringValue("");
		if (!expected.equals(basis)) {
			throw new AssertionError(id + ": the Java says \"" + basis + "\", not \"" + expected + "\"");
		}
		JsonNode calls = javaLine.path(ITEMS).path(0).path("quantity");
		JsonNode pythons = pythonLine.path(ITEMS).path(0).path("quantity");
		if (listed == null && calls.isNumber() && !JsonDiff.close(calls.doubleValue(), pythons.doubleValue())) {
			throw new AssertionError(id + ": the Java bills " + calls + " calls, Python " + pythons);
		}
		double change = pythonLine.path(MONTHLY).doubleValue() - javaLine.path(MONTHLY).doubleValue();
		javaLine.removeAll();
		javaLine.setAll((ObjectNode) pythonLine.deepCopy());
		return change;
	}

	/**
	 * Puts Python's view of what is priced in a cost some of whose API lines departed.
	 * @param java the Java's cost, changed in place
	 * @param python Python's cost
	 * @param departed the ids of the departing lines the Java did not price, in its order
	 * @throws AssertionError when the Java's list of parts not priced is not those lines,
	 * it says no part has a price where another line has one, or its notes differ from
	 * Python's in more than the notes on prices
	 */
	static void putPriced(ObjectNode java, JsonNode python, List<String> departed) {
		List<String> unpriced = new ArrayList<>();
		java.path("unpriced").forEach((id) -> unpriced.add(id.stringValue("")));
		if (!unpriced.equals(departed)) {
			throw new AssertionError("the Java leaves out " + unpriced + ", not the departing " + departed);
		}
		boolean javaPriced = java.path("hasCost").booleanValue();
		boolean pythonPriced = python.path("hasCost").booleanValue();
		if (javaPriced != pythonPriced && (javaPriced || anyOtherPriced(java, departed))) {
			throw new AssertionError("the Java says whether a part has a price otherwise: " + javaPriced);
		}
		List<String> javaNotes = notes(java).stream().filter((note) -> !isApiNote(note)).toList();
		List<String> pythonNotes = notes(python);
		if (!lessOneSided(javaNotes, pythonNotes).equals(lessOneSided(pythonNotes, javaNotes))) {
			throw new AssertionError("the Java's notes differ from Python's in more than prices: " + javaNotes);
		}
		java.remove("unpriced");
		java.set("hasCost", python.path("hasCost"));
		java.set(NOTES, python.path(NOTES));
	}

	/**
	 * Whether the Java did not price an API line.
	 * @param javaLine the Java's line
	 * @return whether it has no unit price and no source
	 */
	static boolean unpriced(JsonNode javaLine) {
		return javaLine.path(SOURCE).isNull() && javaLine.path(UNIT_PRICE).isNull();
	}

	// whether a line's unit price is a preset price of its technology
	private static boolean isPresetPrice(JsonNode line) {
		JsonNode price = line.path(UNIT_PRICE);
		String technology = line.path("technology").stringValue("");
		return price.isNumber() && PRESETS.stream()
			.filter((preset) -> preset.technology().equals(technology))
			.map((preset) -> preset.setting("costPerMillionRequests"))
			.anyMatch((set) -> set.isPresent() && set.get().doubleValue() == price.doubleValue());
	}

	// whether a line that did not depart has a price
	private static boolean anyOtherPriced(JsonNode cost, List<String> departed) {
		for (JsonNode line : cost.path("byNode")) {
			if (!departed.contains(line.path("id").stringValue("")) && line.path(UNIT_PRICE).isNumber()) {
				return true;
			}
		}
		return false;
	}

	// the notes, less each note on prices whose kind the other side lacks
	private static List<String> lessOneSided(List<String> notes, List<String> other) {
		return notes.stream().filter((note) -> !isPriceNote(note) || hasItsKind(other, note)).toList();
	}

	private static boolean hasItsKind(List<String> notes, String note) {
		return PRICE_NOTES.stream()
			.filter(note::startsWith)
			.anyMatch((marker) -> notes.stream().anyMatch((theirs) -> theirs.startsWith(marker)));
	}

	private static boolean isPriceNote(String note) {
		return PRICE_NOTES.stream().anyMatch(note::startsWith);
	}

	private static boolean isApiNote(String note) {
		return API_NOTES.stream().anyMatch((pattern) -> pattern.matcher(note).matches());
	}

	private static List<String> notes(JsonNode cost) {
		List<String> notes = new ArrayList<>();
		cost.path(NOTES).forEach((note) -> notes.add(Objects.requireNonNull(note.stringValue(""))));
		return notes;
	}

	private static boolean isZero(JsonNode price) {
		return price.isNumber() && price.doubleValue() == 0;
	}

}
