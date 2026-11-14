package io.archsim.tools.prices;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.Map;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.numeric.PythonNumbers;
import io.archsim.model.text.PythonText;

/**
 * Python's view of the values {@code json.loads} gives, as {@code scripts/prices} used it
 * on the price APIs' pages and on snapshots: truth, {@code float()}, {@code int()},
 * {@code str()} and {@code ==}. A missing key reads as Python's {@code None}, as
 * {@code dict.get} gave it.
 */
final class JsonValues {

	private static final String NONE = "None";

	private JsonValues() {
	}

	/**
	 * Whether a value is true in Python: not {@code None}, {@code False}, zero or empty.
	 * @param value the value
	 * @return whether it is true
	 */
	static boolean isTrue(JsonNode value) {
		if (value.isNumber()) {
			return value.isIntegralNumber() ? value.bigIntegerValue().signum() != 0 : value.doubleValue() != 0;
		}
		if (value.isBoolean()) {
			return value.booleanValue();
		}
		if (value.isString()) {
			return !value.stringValue().isEmpty();
		}
		return value.isContainer() && !value.isEmpty();
	}

	/**
	 * {@code float(value)}: a number's float, text read as a float.
	 * @param value the value
	 * @return its float
	 * @throws NumberFormatException when it is text that is not a float
	 * @throws PriceListException when it is {@code None}, a list or an object
	 */
	static double floatOf(JsonNode value) {
		if (value.isString()) {
			return PythonNumbers.parseFloat(value.stringValue());
		}
		if (value.isBoolean()) {
			return value.booleanValue() ? 1 : 0;
		}
		if (!value.isNumber()) {
			throw new PriceListException("float() of " + str(value));
		}
		return value.isIntegralNumber() ? value.bigIntegerValue().doubleValue() : value.doubleValue();
	}

	/**
	 * {@code float(value or 0)}.
	 * @param value the value
	 * @return its float, or 0 when it is not true
	 * @throws NumberFormatException when it is text that is not a float
	 * @throws PriceListException when it is a list or an object
	 */
	static double floatOrZero(JsonNode value) {
		return isTrue(value) ? floatOf(value) : 0;
	}

	/**
	 * {@code int(value or 0)}: text read as a whole number, a float truncated.
	 * @param value the value
	 * @return its integer, or 0 when it is not true
	 * @throws NumberFormatException when it is text that is not a whole number, or a
	 * float that is not finite
	 * @throws PriceListException when it is a list or an object
	 */
	static BigInteger intOrZero(JsonNode value) {
		if (!isTrue(value)) {
			return BigInteger.ZERO;
		}
		if (value.isString()) {
			return PythonNumbers.parseInt(value.stringValue());
		}
		if (value.isBoolean()) {
			return BigInteger.ONE;
		}
		if (!value.isNumber()) {
			throw new PriceListException("int() of " + str(value));
		}
		return value.isIntegralNumber() ? value.bigIntegerValue() : new BigDecimal(value.doubleValue()).toBigInteger();
	}

	/**
	 * {@code str(value)}: text as it is, {@code None} for a missing key or null.
	 * @param value the value
	 * @return its text
	 */
	static String str(JsonNode value) {
		return value.isMissingNode() ? NONE : PythonText.str(value);
	}

	/**
	 * {@code str(value or "")}.
	 * @param value the value
	 * @return its text, or empty when it is not true
	 */
	static String strOrEmpty(JsonNode value) {
		return isTrue(value) ? str(value) : "";
	}

	/**
	 * Whether a value is this text.
	 * @param value the value
	 * @param text the text
	 * @return whether it is text, and this text
	 */
	static boolean isText(JsonNode value, String text) {
		return value.isString() && value.stringValue().equals(text);
	}

	/**
	 * {@code left == right}: numbers by value, so that {@code 1 == 1.0} and
	 * {@code True == 1}, objects whatever the order of their keys, a missing key only to
	 * a missing key or null.
	 * @param left a value
	 * @param right another
	 * @return whether Python finds them equal
	 */
	static boolean equal(JsonNode left, JsonNode right) {
		if (isNumeric(left) && isNumeric(right)) {
			return equalNumbers(left, right);
		}
		if (left.isObject() && right.isObject()) {
			return equalObjects(left, right);
		}
		if (left.isArray() && right.isArray()) {
			return equalArrays(left, right);
		}
		if (isNone(left) || isNone(right)) {
			return isNone(left) && isNone(right);
		}
		return left.equals(right);
	}

	private static boolean isNumeric(JsonNode value) {
		return value.isNumber() || value.isBoolean();
	}

	private static boolean isNone(JsonNode value) {
		return value.isMissingNode() || value.isNull();
	}

	private static boolean equalNumbers(JsonNode left, JsonNode right) {
		if (isNotFinite(left) || isNotFinite(right)) {
			return isNotFinite(left) && isNotFinite(right) && left.doubleValue() == right.doubleValue();
		}
		return exact(left).compareTo(exact(right)) == 0;
	}

	private static boolean isNotFinite(JsonNode value) {
		return value.isFloatingPointNumber() && !Double.isFinite(value.doubleValue());
	}

	// the number's exact value: a float's binary value, not its shortest digits
	private static BigDecimal exact(JsonNode value) {
		if (value.isBoolean()) {
			return value.booleanValue() ? BigDecimal.ONE : BigDecimal.ZERO;
		}
		return value.isIntegralNumber() ? new BigDecimal(value.bigIntegerValue()) : new BigDecimal(value.doubleValue());
	}

	private static boolean equalObjects(JsonNode left, JsonNode right) {
		if (left.size() != right.size()) {
			return false;
		}
		for (Map.Entry<String, JsonNode> entry : left.properties()) {
			if (!right.has(entry.getKey()) || !equal(entry.getValue(), right.path(entry.getKey()))) {
				return false;
			}
		}
		return true;
	}

	private static boolean equalArrays(JsonNode left, JsonNode right) {
		if (left.size() != right.size()) {
			return false;
		}
		Iterator<JsonNode> others = right.iterator();
		for (JsonNode value : left) {
			if (!equal(value, others.next())) {
				return false;
			}
		}
		return true;
	}

}
