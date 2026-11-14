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
 * <li>The cost adds an hour at each load ({@code hourly}) and each line's instances at
 * the peak ({@code peakInstances}): left out.</li>
 * <li>A line whose count is the one a part settles at (a cluster's, or an autoscaled
 * service's, whose basis says {@code (autoscaling 2–20)}) says how it was counted in the
 * Java's words: Python's are put in their place, the leading count only (up to the first
 * {@code "; "} or {@code " on implied "}), and the rest of its basis compared.</li>
 * <li>Where such a count differs from Python's (the cases listed with their reasons), the
 * Java's count must be the one listed; the line, the month and the cost per million
 * requests are priced back at Python's count, and the lines put in Python's order, so the
 * rest is compared exactly.</li>
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

	private CostDepartures() {
	}

	/**
	 * The Java's cost as it compares with the frozen engine's.
	 * @param python the frozen engine's cost
	 * @param java the Java's cost
	 * @param counts the counts that depart from Python's, by line id: what the Java must
	 * count; empty where none departs
	 * @return a copy of the Java's cost, its departures put back
	 * @throws AssertionError when a listed count is not the Java's
	 */
	public static JsonNode comparable(JsonNode python, JsonNode java, Map<String, Double> counts) {
		if (!java.isObject() || !python.isObject()) {
			return java;
		}
		ObjectNode copy = (ObjectNode) java.deepCopy();
		copy.remove("hourly");
		double hours = java.path("hoursPerMonth").doubleValue();
		double repriced = 0.0;
		for (JsonNode line : copy.path(BY_NODE)) {
			ObjectNode javaLine = (ObjectNode) line;
			javaLine.remove("peakInstances");
			JsonNode pythonLine = lineOf(python, javaLine.path("id").stringValue());
			if (pythonLine != null) {
				putWords(javaLine, pythonLine);
				repriced += putCount(javaLine, pythonLine, counts.get(javaLine.path("id").stringValue()), hours);
			}
		}
		if (!counts.isEmpty()) {
			putMonth(copy, repriced);
			putOrder(copy, python);
		}
		return copy;
	}

	private static void putWords(ObjectNode javaLine, JsonNode pythonLine) {
		String basis = javaLine.path("basis").stringValue("");
		boolean settled = "kubernetes_cluster".equals(javaLine.path("type").stringValue()) || basis.contains(SETTLED);
		if (settled) {
			String python = pythonLine.path("basis").stringValue("");
			javaLine.put("basis", python.substring(0, countEnd(python)) + basis.substring(countEnd(basis)));
		}
	}

	// where a basis's leading count ends
	private static int countEnd(String basis) {
		return AFTER_THE_COUNT.stream().mapToInt(basis::indexOf).filter((at) -> at >= 0).min().orElse(basis.length());
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

}
