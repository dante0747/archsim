package io.archsim.engine.provenance;

import java.util.Iterator;
import java.util.TreeSet;
import java.util.function.BiPredicate;

import tools.jackson.databind.JsonNode;

/**
 * Comparing JSON values as the Python engine compares the dictionaries and lists it reads
 * them into. A missing value is Python's {@code None}, as JSON {@code null} is.
 */
final class Values {

	/** {@code math.isclose}'s relative tolerance, as {@code same_value} calls it. */
	private static final double RELATIVE_TOLERANCE = 1e-9;

	/** {@code math.isclose}'s absolute tolerance, as {@code same_value} calls it. */
	private static final double ABSOLUTE_TOLERANCE = 1e-12;

	private Values() {
	}

	/**
	 * The engine's {@code _same}, which is Python's {@code ==} with numbers compared as
	 * floats: numbers by value, a switch as 1 or 0 (Python's {@code True == 1}), objects
	 * and lists item by item.
	 * @param first a value
	 * @param second another value
	 * @return whether Python finds them equal
	 */
	static boolean equal(JsonNode first, JsonNode second) {
		if (isNone(first) || isNone(second)) {
			return isNone(first) && isNone(second);
		}
		if (isNumeric(first) && isNumeric(second)) {
			return numbersEqual(first, second);
		}
		if (first.isContainer()) {
			return containersMatch(first, second, Values::equal);
		}
		return first.isString() && first.equals(second);
	}

	/**
	 * The engine's {@code same_value}: equal up to how a number is written ({@code 6} and
	 * {@code 6.0}) and float noise, a switch only equal to a switch.
	 * @param first a value
	 * @param second another value
	 * @return whether they are the same value
	 */
	static boolean same(JsonNode first, JsonNode second) {
		if (first.isBoolean() || second.isBoolean()) {
			return first.isBoolean() && second.isBoolean() && first.booleanValue() == second.booleanValue();
		}
		if (first.isNumber() && second.isNumber()) {
			return isClose(first.doubleValue(), second.doubleValue());
		}
		if (first.isContainer() && second.isContainer()) {
			return containersMatch(first, second, Values::same);
		}
		return equal(first, second);
	}

	/**
	 * Whether a value is Python's {@code None}.
	 * @param value a value, or a missing node
	 * @return whether it is JSON {@code null} or not there
	 */
	static boolean isNone(JsonNode value) {
		return value.isNull() || value.isMissingNode();
	}

	private static boolean isNumeric(JsonNode value) {
		return value.isNumber() || value.isBoolean();
	}

	/**
	 * Two numbers compared as floats, a switch counting as 1 or 0, as {@code _same}
	 * compares them. Whole numbers beyond 2<sup>53</sup>, which Python's {@code ==}
	 * compares exactly inside a list or object, do not occur in a setting.
	 * @param first a number or a switch
	 * @param second another
	 * @return whether they are equal
	 */
	private static boolean numbersEqual(JsonNode first, JsonNode second) {
		return number(first) == number(second);
	}

	private static double number(JsonNode value) {
		if (value.isBoolean()) {
			return value.booleanValue() ? 1 : 0;
		}
		return value.doubleValue();
	}

	/**
	 * Python's {@code math.isclose} with the engine's tolerances.
	 * @param first a number
	 * @param second another
	 * @return whether they are close
	 */
	private static boolean isClose(double first, double second) {
		if (first == second) {
			return true;
		}
		if (Double.isInfinite(first) || Double.isInfinite(second)) {
			return false;
		}
		double difference = Math.abs(second - first);
		return difference <= Math.abs(RELATIVE_TOLERANCE * second) || difference <= Math.abs(RELATIVE_TOLERANCE * first)
				|| difference <= ABSOLUTE_TOLERANCE;
	}

	/**
	 * Two lists item by item, or two objects key by key; a list and an object differ.
	 * @param first a list or an object
	 * @param second another value
	 * @param match how two items compare
	 * @return whether they match
	 */
	private static boolean containersMatch(JsonNode first, JsonNode second, BiPredicate<JsonNode, JsonNode> match) {
		if (first.isArray() && second.isArray()) {
			return itemsMatch(first, second, match);
		}
		return first.isObject() && second.isObject() && propertiesMatch(first, second, match);
	}

	private static boolean itemsMatch(JsonNode first, JsonNode second, BiPredicate<JsonNode, JsonNode> match) {
		if (first.size() != second.size()) {
			return false;
		}
		Iterator<JsonNode> others = second.iterator();
		for (JsonNode item : first) {
			if (!match.test(item, others.next())) {
				return false;
			}
		}
		return true;
	}

	private static boolean propertiesMatch(JsonNode first, JsonNode second, BiPredicate<JsonNode, JsonNode> match) {
		return new TreeSet<>(first.propertyNames()).equals(new TreeSet<>(second.propertyNames())) && first.properties()
			.stream()
			.allMatch((property) -> match.test(property.getValue(), second.get(property.getKey())));
	}

}
