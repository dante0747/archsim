package io.archsim.benchmarks.values;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;

import io.archsim.engine.numeric.PythonRound;

/**
 * Python's arithmetic on the JSON values the results hold, so that a number computed from
 * them is the number the Python harness wrote: an integer stays an integer through
 * {@code +}, {@code -}, {@code *}, {@code abs}, {@code round(x, n)}, {@code min},
 * {@code max} and the middle of a median, {@code /} is a float, and None is JSON's
 * {@code null} (ADR-0070).
 */
public final class PythonValues {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final JsonNode TWO = NODES.numberNode(2);

	private PythonValues() {
	}

	/**
	 * None, as the results write it.
	 * @return JSON's {@code null}
	 */
	public static JsonNode none() {
		return NullNode.instance;
	}

	/**
	 * Whether a value is None: JSON's {@code null}, or a key that is not there.
	 * @param value the value, {@code null} when not there
	 * @return whether it is None
	 */
	public static boolean isNone(@Nullable JsonNode value) {
		return value == null || value.isNull() || value.isMissingNode();
	}

	/**
	 * {@code object.get(key)}: a field of an object, None when the key is not there.
	 * @param object the object
	 * @param key the key
	 * @return its value, or None
	 */
	public static JsonNode get(JsonNode object, String key) {
		JsonNode value = object.get(key);
		return (value != null) ? value : none();
	}

	/**
	 * {@code (object.get(key) or 0)}: a field's number, zero when it is None.
	 * @param object the object
	 * @param key the key
	 * @return its value as a float, or zero
	 */
	public static double numberOrZero(JsonNode object, String key) {
		JsonNode value = get(object, key);
		return isNone(value) ? 0 : value.doubleValue();
	}

	/**
	 * A float.
	 * @param value its value
	 * @return the JSON number
	 */
	public static JsonNode number(double value) {
		return NODES.numberNode(value);
	}

	/**
	 * An integer.
	 * @param value its value
	 * @return the JSON number
	 */
	public static JsonNode integer(long value) {
		return (value == (int) value) ? NODES.numberNode((int) value) : NODES.numberNode(value);
	}

	/**
	 * {@code left + right}.
	 * @param left a number
	 * @param right a number
	 * @return an integer when both are, else a float
	 * @throws ArithmeticException when an integer result does not fit in a {@code long},
	 * where Python's would grow
	 */
	public static JsonNode add(JsonNode left, JsonNode right) {
		return bothIntegers(left, right) ? integer(Math.addExact(left.longValue(), right.longValue()))
				: number(left.doubleValue() + right.doubleValue());
	}

	/**
	 * {@code left - right}.
	 * @param left a number
	 * @param right a number
	 * @return an integer when both are, else a float
	 * @throws ArithmeticException when an integer result does not fit in a {@code long},
	 * where Python's would grow
	 */
	public static JsonNode subtract(JsonNode left, JsonNode right) {
		return bothIntegers(left, right) ? integer(Math.subtractExact(left.longValue(), right.longValue()))
				: number(left.doubleValue() - right.doubleValue());
	}

	/**
	 * {@code left * right}.
	 * @param left a number
	 * @param right a number
	 * @return an integer when both are, else a float
	 * @throws ArithmeticException when an integer result does not fit in a {@code long},
	 * where Python's would grow
	 */
	public static JsonNode multiply(JsonNode left, JsonNode right) {
		return bothIntegers(left, right) ? integer(Math.multiplyExact(left.longValue(), right.longValue()))
				: number(left.doubleValue() * right.doubleValue());
	}

	/**
	 * {@code left / right}, Python's true division: of integers below 2^53 the same
	 * correctly rounded quotient as of the floats they convert to.
	 * @param left a number
	 * @param right a number, not zero
	 * @return a float
	 */
	public static JsonNode divide(JsonNode left, JsonNode right) {
		return number(left.doubleValue() / right.doubleValue());
	}

	/**
	 * {@code abs(value)}.
	 * @param value a number
	 * @return an integer when it is one, else a float
	 * @throws ArithmeticException for the smallest {@code long}, whose magnitude does not
	 * fit
	 */
	public static JsonNode abs(JsonNode value) {
		return value.isIntegralNumber() ? integer(Math.absExact(value.longValue()))
				: number(Math.abs(value.doubleValue()));
	}

	/**
	 * {@code round(value, digits)}: an integer unchanged, a float's exact binary value
	 * rounded half to even.
	 * @param value a number
	 * @param digits the decimals to keep
	 * @return the rounded number, of the value's type
	 */
	public static JsonNode round(JsonNode value, int digits) {
		return value.isIntegralNumber() ? value : number(PythonRound.round(value.doubleValue(), digits));
	}

	/**
	 * How two numbers compare, as Python compares them: {@code 0.0} equals {@code -0.0}
	 * and {@code 2} equals {@code 2.0}.
	 * @param left a number
	 * @param right a number
	 * @return negative, zero or positive
	 */
	public static int compare(JsonNode left, JsonNode right) {
		double leftValue = left.doubleValue();
		double rightValue = right.doubleValue();
		if (leftValue < rightValue) {
			return -1;
		}
		return (leftValue > rightValue) ? 1 : 0;
	}

	/**
	 * {@code max(values)}: the first of the largest.
	 * @param values numbers, at least one
	 * @return the largest
	 */
	public static JsonNode max(List<JsonNode> values) {
		JsonNode largest = values.getFirst();
		for (JsonNode value : values) {
			if (compare(value, largest) > 0) {
				largest = value;
			}
		}
		return largest;
	}

	/**
	 * {@code min(values)}: the first of the smallest.
	 * @param values numbers, at least one
	 * @return the smallest
	 */
	public static JsonNode min(List<JsonNode> values) {
		JsonNode smallest = values.getFirst();
		for (JsonNode value : values) {
			if (compare(value, smallest) < 0) {
				smallest = value;
			}
		}
		return smallest;
	}

	/**
	 * The median of the values that are not None, as {@code statistics.median} finds it:
	 * the middle one, or the two middle ones' sum over two; None when there are none or
	 * when the median is infinite (most of them were).
	 * @param values numbers or None
	 * @return the median, or None
	 */
	public static JsonNode median(List<JsonNode> values) {
		List<JsonNode> sorted = new ArrayList<>();
		for (JsonNode value : values) {
			if (!isNone(value)) {
				sorted.add(value);
			}
		}
		if (sorted.isEmpty()) {
			return none();
		}
		sorted.sort(PythonValues::compare);
		int middle = sorted.size() / 2;
		JsonNode median = (sorted.size() % 2 == 1) ? sorted.get(middle)
				: divide(add(sorted.get(middle - 1), sorted.get(middle)), TWO);
		return Double.isInfinite(median.doubleValue()) ? none() : median;
	}

	private static boolean bothIntegers(JsonNode left, JsonNode right) {
		return left.isIntegralNumber() && right.isIntegralNumber();
	}

}
