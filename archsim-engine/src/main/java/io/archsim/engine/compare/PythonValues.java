package io.archsim.engine.compare;

import java.math.BigDecimal;
import java.util.Iterator;

import tools.jackson.databind.JsonNode;

/**
 * Python's {@code ==} on the values {@code json.loads} gives: numbers equal by value
 * whatever their kind ({@code 2 == 2.0}, {@code True == 1}), {@code null} equal only to
 * {@code null}, lists item by item, and objects key by key in any order.
 */
final class PythonValues {

	private PythonValues() {
	}

	/**
	 * Whether Python counts two JSON values as equal.
	 * @param first a value; a missing one is {@code None}
	 * @param second another value; a missing one is {@code None}
	 * @return whether they are equal
	 */
	static boolean equal(JsonNode first, JsonNode second) {
		if (none(first) || none(second)) {
			return none(first) && none(second);
		}
		if (numeric(first) && numeric(second)) {
			return number(first).compareTo(number(second)) == 0;
		}
		if (first.isString() && second.isString()) {
			return first.stringValue().equals(second.stringValue());
		}
		return containersEqual(first, second);
	}

	/**
	 * Whether two lists are equal item by item, or two objects key by key.
	 * @param first a value
	 * @param second another value
	 * @return whether they are equal lists or objects; {@code false} for any other values
	 */
	private static boolean containersEqual(JsonNode first, JsonNode second) {
		if (first.isArray() && second.isArray()) {
			return first.size() == second.size() && itemsEqual(first.iterator(), second.iterator());
		}
		if (first.isObject() && second.isObject()) {
			return first.size() == second.size() && first.propertyStream()
				.allMatch((property) -> second.has(property.getKey())
						&& equal(property.getValue(), second.get(property.getKey())));
		}
		return false;
	}

	private static boolean itemsEqual(Iterator<JsonNode> first, Iterator<JsonNode> second) {
		while (first.hasNext()) {
			if (!equal(first.next(), second.next())) {
				return false;
			}
		}
		return true;
	}

	private static boolean none(JsonNode value) {
		return value.isNull() || value.isMissingNode();
	}

	/**
	 * Whether Python compares the value as a number: a number, or a boolean, which is an
	 * {@code int}.
	 * @param value the value
	 * @return whether it is
	 */
	private static boolean numeric(JsonNode value) {
		return value.isNumber() || value.isBoolean();
	}

	/**
	 * A number's exact value, as Python compares an {@code int} with a {@code float}: a
	 * boolean is 0 or 1, and a fraction is the float {@code json.loads} reads.
	 * @param value a number or a boolean
	 * @return its exact value
	 */
	private static BigDecimal number(JsonNode value) {
		if (value.isBoolean()) {
			return value.booleanValue() ? BigDecimal.ONE : BigDecimal.ZERO;
		}
		if (value.isIntegralNumber()) {
			return new BigDecimal(value.bigIntegerValue());
		}
		return new BigDecimal(value.doubleValue());
	}

}
