package io.archsim.engine.parity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where a cost of the Java engine departs from the frozen engine's, by ADR-0074, and how
 * it is compared with it all the same.
 * <ul>
 * <li>The cost adds the hours it bills at the peak ({@code peakHours}), an hour at each
 * load ({@code hourly}) and each line's instances at the peak ({@code peakInstances}):
 * left out.</li>
 * <li>A line whose count is the one a part settles at (a cluster's, or an autoscaled
 * service's, whose basis says {@code (autoscaling 2–20)}) says how it was counted in the
 * Java's words: Python's are put in their place, the leading count only (up to the first
 * {@code "; "} or {@code " on implied "}), and the rest of its basis compared.</li>
 * <li>Where such a count differs from Python's (the cases listed with their reasons), the
 * Java's count must be the one listed; the line, the month and the cost per million
 * requests are priced back at Python's count, and the lines put in Python's order, so the
 * rest is compared exactly.</li>
 * <li>Where no bucket of a run's window is at the base load, the Java measures the base's
 * per-request rates in the buckets nearest it, and Python over the whole window. A line
 * whose rate departs so (the cases listed with their reasons) must say what is listed and
 * cost what Python's costs, item by item; its basis and its items' quantities are then
 * Python's.</li>
 * </ul>
 */
public final class CostDepartures {

	private static final String SETTLED = "(autoscaling ";

	/** What follows a line's count in its basis: a note, or where its pods run. */
	private static final List<String> AFTER_THE_COUNT = List.of("; ", " on implied ");

	private static final String BY_NODE = "byNode";

	private static final String INSTANCES = "instances";

	private static final String MONTHLY = "monthly";

	private static final String PER_MILLION = "perMillionRequests";

	private static final String BASIS = "basis";

	private static final String ITEMS = "items";

	private CostDepartures() {
	}

	/**
	 * The Java's cost as it compares with the frozen engine's.
	 * @param python the frozen engine's cost
	 * @param java the Java's cost
	 * @param departing the case's lines that depart from Python's
	 * @return a copy of the Java's cost, its departures put back
	 * @throws AssertionError when a listed count or basis is not the Java's, or a line
	 * whose basis is listed costs other than Python's
	 */
	public static JsonNode comparable(JsonNode python, JsonNode java, Departing departing) {
		if (!java.isObject() || !python.isObject()) {
			return java;
		}
		Map<String, Double> counts = departing.counts();
		ObjectNode copy = (ObjectNode) java.deepCopy();
		copy.remove("hourly");
		copy.remove("peakHours");
		double hours = java.path("hoursPerMonth").doubleValue();
		double repriced = 0.0;
		for (JsonNode line : copy.path(BY_NODE)) {
			ObjectNode javaLine = (ObjectNode) line;
			javaLine.remove("peakInstances");
			JsonNode pythonLine = lineOf(python, javaLine.path("id").stringValue());
			if (pythonLine != null) {
				String id = javaLine.path("id").stringValue();
				putWords(javaLine, pythonLine);
				putRates(javaLine, pythonLine, departing.bases().get(id));
				repriced += putCount(javaLine, pythonLine, counts.get(id), hours);
			}
		}
		if (!counts.isEmpty()) {
			putMonth(copy, repriced);
			putOrder(copy, python);
		}
		return copy;
	}

	private static void putWords(ObjectNode javaLine, JsonNode pythonLine) {
		String basis = javaLine.path(BASIS).stringValue("");
		boolean settled = "kubernetes_cluster".equals(javaLine.path("type").stringValue()) || basis.contains(SETTLED);
		if (settled) {
			String python = pythonLine.path(BASIS).stringValue("");
			javaLine.put(BASIS, python.substring(0, countEnd(python)) + basis.substring(countEnd(basis)));
		}
	}

	// where a basis's leading count ends
	private static int countEnd(String basis) {
		return AFTER_THE_COUNT.stream().mapToInt(basis::indexOf).filter((at) -> at >= 0).min().orElse(basis.length());
	}

	/**
	 * Puts Python's basis and item quantities in a line whose rates at the base load are
	 * listed as departing, once it is shown to say what is listed and cost what Python's
	 * costs, item by item.
	 * @param javaLine the Java's line, changed in place
	 * @param pythonLine Python's line
	 * @param listed what the Java's line must say; {@code null} when its rates do not
	 * depart
	 */
	private static void putRates(ObjectNode javaLine, JsonNode pythonLine, @Nullable String listed) {
		if (listed == null) {
			return;
		}
		String id = javaLine.path("id").stringValue();
		String basis = javaLine.path(BASIS).stringValue();
		if (!listed.equals(basis)) {
			throw new AssertionError(id + ": the Java says \"" + basis + "\", not the \"" + listed + "\" listed");
		}
		if (!Priced.of(javaLine).equals(Priced.of(pythonLine))) {
			throw new AssertionError(
					id + ": the Java prices " + Priced.of(javaLine) + ", Python " + Priced.of(pythonLine));
		}
		JsonNode items = pythonLine.path(ITEMS);
		javaLine.set(BASIS, pythonLine.path(BASIS));
		for (int i = 0; i < items.size(); i++) {
			ObjectNode item = (ObjectNode) javaLine.path(ITEMS).get(i);
			item.set("quantity", items.get(i).path("quantity"));
			item.set("text", items.get(i).path("text"));
		}
	}

	/**
	 * Prices a line back at Python's count where its count is listed as departing.
	 * @param javaLine the Java's line, changed in place
	 * @param pythonLine Python's line
	 * @param listed what the Java must count; {@code null} when the line does not depart
	 * @param hours the hours a month is billed for
	 * @return what the line's month changed by
	 */
	private static double putCount(ObjectNode javaLine, JsonNode pythonLine, @Nullable Double listed, double hours) {
		if (listed == null) {
			return 0.0;
		}
		double count = javaLine.path(INSTANCES).doubleValue();
		if (count != listed) {
			throw new AssertionError(javaLine.path("id").stringValue() + ": the Java counts " + count + ", not the "
					+ listed + " listed");
		}
		double change = (pythonLine.path(INSTANCES).doubleValue() - count) * javaLine.path("unitPrice").doubleValue()
				* hours;
		javaLine.set(INSTANCES, pythonLine.path(INSTANCES));
		javaLine.put(MONTHLY, javaLine.path(MONTHLY).doubleValue() + change);
		return change;
	}

	private static void putMonth(ObjectNode cost, double repriced) {
		double monthly = cost.path(MONTHLY).doubleValue();
		double back = monthly + repriced;
		cost.put(MONTHLY, back);
		JsonNode perMillion = cost.path(PER_MILLION);
		if (perMillion.isNumber()) {
			cost.put(PER_MILLION, perMillion.doubleValue() * back / monthly);
		}
	}

	// a line priced back may take another place among the dearest first: Python's order
	private static void putOrder(ObjectNode cost, JsonNode python) {
		List<JsonNode> ordered = new ArrayList<>();
		for (JsonNode pythonLine : python.path(BY_NODE)) {
			JsonNode javaLine = lineOf(cost, pythonLine.path("id").stringValue());
			if (javaLine != null) {
				ordered.add(javaLine);
			}
		}
		if (ordered.size() == cost.path(BY_NODE).size()) {
			ArrayNode lines = cost.putArray(BY_NODE);
			ordered.forEach(lines::add);
		}
	}

	private static @Nullable JsonNode lineOf(JsonNode cost, String id) {
		for (JsonNode line : cost.path(BY_NODE)) {
			if (id.equals(line.path("id").stringValue())) {
				return line;
			}
		}
		return null;
	}

	/**
	 * What a cost line costs: its month, and each item's name, price and month.
	 *
	 * @param monthly the line's month
	 * @param items its items, in order
	 */
	private record Priced(double monthly, List<Item> items) {

		static Priced of(JsonNode line) {
			List<Item> items = new ArrayList<>();
			for (JsonNode item : line.path(ITEMS)) {
				items.add(new Item(item.path("what").stringValue(""), item.path("unitPrice").doubleValue(),
						item.path(MONTHLY).doubleValue()));
			}
			return new Priced(line.path(MONTHLY).doubleValue(), items);
		}

		/**
		 * What one item costs.
		 *
		 * @param what what it counts
		 * @param unitPrice its price
		 * @param monthly its month
		 */
		record Item(String what, double unitPrice, double monthly) {
		}

	}

	/**
	 * The lines of one case that depart from the frozen engine's.
	 *
	 * @param counts what the Java counts where a part's count departs, by line id
	 * @param bases what the Java's line says where its rates at the base load depart, by
	 * line id
	 */
	public record Departing(Map<String, Double> counts, Map<String, String> bases) {

		/** A case where no line departs. */
		public static final Departing NONE = new Departing(Map.of(), Map.of());

	}

}
