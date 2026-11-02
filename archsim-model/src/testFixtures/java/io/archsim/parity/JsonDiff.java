package io.archsim.parity;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;

/**
 * Where two JSON values differ, by the port's parity bar (ADR-0005): text, booleans and
 * structure exactly, numbers within a relative {@value #TOLERANCE}, an integer and a
 * float alike when equal. It is the golden exporter's {@code --check} comparison, so Java
 * and Python agree on "the same".
 */
public final class JsonDiff {

	/** The relative tolerance for numbers. */
	public static final double TOLERANCE = 1e-9;

	private static final int LIMIT = 8;

	private JsonDiff() {
	}

	/**
	 * JSON Pointers where {@code actual} differs from {@code expected} (at most eight);
	 * empty when it does not.
	 */
	public static List<String> differences(JsonNode expected, JsonNode actual) {
		List<String> found = new ArrayList<>();
		walk(expected, actual, "", found);
		return found;
	}

	/** Whether two numbers are equal within the tolerance. */
	public static boolean close(double first, double second) {
		return first == second || Math.abs(first - second) <= TOLERANCE * Math.max(Math.abs(first), Math.abs(second));
	}

	private static void walk(JsonNode expected, JsonNode actual, String pointer, List<String> found) {
		if (found.size() >= LIMIT) {
			return;
		}
		if (expected.isObject() && actual.isObject()) {
			walkProperties(expected, actual, pointer, found);
		}
		else if (expected.isArray() && actual.isArray() && expected.size() == actual.size()) {
			walkItems(expected, actual, pointer, found);
		}
		else if (!same(expected, actual)) {
			found.add(pointer.isEmpty() ? "/" : pointer);
		}
	}

	private static void walkProperties(JsonNode expected, JsonNode actual, String pointer, List<String> found) {
		TreeSet<String> keys = new TreeSet<>(expected.propertyNames());
		keys.addAll(actual.propertyNames());
		for (String key : keys) {
			String child = pointer + "/" + key.replace("~", "~0").replace("/", "~1");
			if (!expected.has(key) || !actual.has(key)) {
				found.add(child + (expected.has(key) ? " (missing)" : " (unexpected)"));
			}
			else {
				walk(expected.get(key), actual.get(key), child, found);
			}
		}
	}

	private static void walkItems(JsonNode expected, JsonNode actual, String pointer, List<String> found) {
		for (int i = 0; i < expected.size(); i++) {
			walk(expected.get(i), actual.get(i), pointer + "/" + i, found);
		}
	}

	private static boolean same(JsonNode expected, JsonNode actual) {
		if (expected.isNumber() && actual.isNumber()) {
			return (expected.isIntegralNumber() && actual.isIntegralNumber())
					? expected.bigIntegerValue().equals(actual.bigIntegerValue())
					: close(expected.doubleValue(), actual.doubleValue());
		}
		return expected.isValueNode() && actual.isValueNode() && expected.equals(actual);
	}

}
