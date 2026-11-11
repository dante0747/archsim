package io.archsim.engine.sizing.audit;

import tools.jackson.databind.JsonNode;

/**
 * A report's values read as the sizing audit read them in Python: a number that is
 * missing, null or 0 gives its fallback ({@code value or fallback}), and a value's truth
 * is Python's.
 */
final class AuditJson {

	private AuditJson() {
	}

	/**
	 * A number, as Python's {@code value or fallback} reads it.
	 * @param value the value
	 * @param fallback what a missing, null or zero value gives
	 * @return the number
	 */
	static double number(JsonNode value, double fallback) {
		return (value.isNumber() && value.doubleValue() != 0) ? value.doubleValue() : fallback;
	}

	/**
	 * A value's truth in Python: a true boolean, a number other than 0, a string, list or
	 * object that is not empty.
	 * @param value the value
	 * @return whether it is true
	 */
	static boolean truthy(JsonNode value) {
		if (value.isBoolean()) {
			return value.booleanValue();
		}
		if (value.isNumber()) {
			return value.doubleValue() != 0;
		}
		if (value.isString()) {
			return !value.stringValue().isEmpty();
		}
		return value.isContainer() && !value.isEmpty();
	}

	/**
	 * A value's text.
	 * @param value the value
	 * @return its text; empty when it is not a string (missing, null, a number)
	 */
	static String text(JsonNode value) {
		return value.isString() ? value.stringValue() : "";
	}

	/**
	 * Whether a value is the text given.
	 * @param value the value
	 * @param expected the text
	 * @return whether it is a string equal to the text
	 */
	static boolean is(JsonNode value, String expected) {
		return expected.equals(text(value));
	}

}
