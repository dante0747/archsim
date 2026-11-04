package io.archsim.engine.provenance;

import java.util.Set;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * How an input's value reads where the analysis says where it came from: a number with
 * its unit ({@code 5 ms}, {@code 12345.7 req/s}), a switch as {@code on} or {@code off},
 * a traffic pattern by its kind ({@code pattern burst}), and anything else as Python's
 * {@code str()} writes it. This is how a value read from a node's JSON config reads: a
 * distribution or pattern of the document's records reads as its own description
 * ({@code Distribution.describe()}), so pass it that way, not as its JSON.
 */
public final class ValueText {

	/** The traffic patterns told apart from other objects by their kind alone. */
	private static final Set<String> PATTERNS_WITHOUT_POINTS = Set.of("ramp", "burst");

	private ValueText() {
	}

	/**
	 * The text of a value.
	 * @param value the value, as JSON
	 * @param unit its unit, written after a number, or {@code null} for none
	 * @return how it reads
	 */
	public static String describe(JsonNode value, @Nullable String unit) {
		if (value.isObject() && value.has("kind")) {
			return isPattern(value) ? "pattern " + PythonText.str(value.get("kind")) : PythonText.str(value);
		}
		if (value.isBoolean()) {
			return value.booleanValue() ? "on" : "off";
		}
		if (!value.isNumber()) {
			return PythonText.str(value);
		}
		String number = value.isIntegralNumber() ? PythonText.str(value)
				: PythonFormat.format(value.doubleValue(), "g");
		return (unit == null || unit.isEmpty()) ? number : number + " " + unit;
	}

	private static boolean isPattern(JsonNode value) {
		return value.has("points") || PATTERNS_WITHOUT_POINTS.contains(value.get("kind").asString(""));
	}

}
