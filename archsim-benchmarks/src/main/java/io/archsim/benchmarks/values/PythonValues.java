package io.archsim.benchmarks.values;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

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
	 * A value that may be absent, as Python's None when it is.
	 * @param value the value, or empty
	 * @return the value, or None
	 */
	public static JsonNode orNone(Optional<? extends JsonNode> value) {
		return value.isPresent() ? value.get() : none();
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
	 * {@code left == right} for JSON values: numbers by value ({@code 2 == 2.0},
	 * {@code 0.0 == -0.0}; two integers exactly, an integer and a float as doubles, where
	 * Python compares them exactly), objects by their keys in any order, arrays item by
	 * item, None only to None. Not a number equals nothing, even inside an object or an
	 * array, where Python finds the one NaN object its json reads equal to itself; and
	 * unlike Python, where {@code True == 1}, a boolean equals only a boolean. The
	 * results compare neither.
	 * @param left a value
	 * @param right a value
	 * @return whether they are equal: Python's {@code ==}, but for those cases
	 */
	public static boolean equal(JsonNode left, JsonNode right) {
		if (isNone(left) || isNone(right)) {
			return isNone(left) && isNone(right);
		}
		if (left.isNumber() && right.isNumber()) {
			return bothIntegers(left, right) ? left.bigIntegerValue().equals(right.bigIntegerValue())
					: left.doubleValue() == right.doubleValue();
		}
		if (left.isContainer() && right.isContainer()) {
			return equalContainers(left, right);
		}
		return left.equals(right);
	}

	// objects key by key in any order, arrays item by item
	private static boolean equalContainers(JsonNode left, JsonNode right) {
		if (left.size() != right.size() || left.isObject() != right.isObject()) {
			return false;
		}
		if (left.isObject()) {
			return left.properties()
				.stream()
				.allMatch((field) -> right.has(field.getKey()) && equal(field.getValue(), right.get(field.getKey())));
		}
		return IntStream.range(0, left.size()).allMatch((index) -> equal(left.get(index), right.get(index)));
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

	/**
	 * {@code sum(values)} as CPython 3.12 adds: integers exactly while every value is
	 * one, then, from the first float on, floats by Neumaier's compensated summation and
	 * any integer after it plainly. Twelve 0.8s add up to 9.600000000000001, not a loop's
	 * 9.6.
	 * @param values numbers
	 * @return an integer when every value is one (0 for none), else a float
	 * @throws ArithmeticException when an integer sum does not fit in a {@code long},
	 * where Python's would grow
	 */
	public static JsonNode sum(List<JsonNode> values) {
		long whole = 0;
		int first = 0;
		while (first < values.size() && values.get(first).isIntegralNumber()) {
			whole = Math.addExact(whole, values.get(first).longValue());
			first++;
		}
		if (first == values.size()) {
			return integer(whole);
		}
		Compensated total = new Compensated((double) whole + values.get(first).doubleValue());
		for (JsonNode value : values.subList(first + 1, values.size())) {
			if (value.isIntegralNumber()) {
				total.addPlainly(value.doubleValue());
			}
			else {
				total.add(value.doubleValue());
			}
		}
		return number(total.sum());
	}

	/**
	 * {@code sum(values)} of floats as CPython 3.12 adds them: from the integer 0, with
	 * Neumaier's compensation, as {@link #sum} adds JSON floats, for values that are not
	 * JSON.
	 * @param values the floats
	 * @return their sum; 0 for none
	 */
	public static double sumOfFloats(List<Double> values) {
		if (values.isEmpty()) {
			return 0.0;
		}
		Compensated total = new Compensated(0.0 + values.getFirst());
		values.subList(1, values.size()).forEach(total::add);
		return total.sum();
	}

	private static boolean bothIntegers(JsonNode left, JsonNode right) {
		return left.isIntegralNumber() && right.isIntegralNumber();
	}

	/**
	 * CPython 3.12's float sum as it runs: the total and what rounding lost on the way,
	 * which Neumaier's compensation adds back once at the end.
	 */
	private static final class Compensated {

		private double total;

		private double compensation;

		Compensated(double start) {
			this.total = start;
		}

		// a float: what rounding loses is kept
		void add(double item) {
			double next = this.total + item;
			this.compensation += (Math.abs(this.total) >= Math.abs(item)) ? (this.total - next) + item
					: (item - next) + this.total;
			this.total = next;
		}

		// an integer after a float, which CPython adds without keeping what rounding
		// loses
		void addPlainly(double item) {
			this.total += item;
		}

		// the compensation, unless it is zero or not finite
		double sum() {
			boolean compensated = this.compensation != 0.0 && Double.isFinite(this.compensation);
			return compensated ? this.total + this.compensation : this.total;
		}

	}

}
