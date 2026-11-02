package io.archsim.model.codec;

import java.lang.reflect.AnnotatedType;
import java.math.BigDecimal;
import java.util.function.IntPredicate;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * The bounds of a number ({@link Ge}, {@link Gt}, {@link Le}, {@link Lt}), checked as
 * pydantic does.
 *
 * @param ge the lowest value allowed, if any
 * @param gt the value the number must be above, if any
 * @param le the highest value allowed, if any
 * @param lt the value the number must be below, if any
 */
record Bounds(@Nullable Double ge, @Nullable Double gt, @Nullable Double le, @Nullable Double lt) {

	/**
	 * Below this magnitude a whole bound is written as an integer in the JSON Schema, as
	 * the Python model declares it.
	 */
	private static final double INTEGRAL_BOUND_LIMIT = 1e15;

	/**
	 * The bounds a number type is annotated with.
	 * @param type a number type, with its annotations
	 * @return its bounds, each {@code null} when it has none
	 */
	static Bounds of(AnnotatedType type) {
		Ge ge = type.getAnnotation(Ge.class);
		Gt gt = type.getAnnotation(Gt.class);
		Le le = type.getAnnotation(Le.class);
		Lt lt = type.getAnnotation(Lt.class);
		return new Bounds((ge != null) ? ge.value() : null, (gt != null) ? gt.value() : null,
				(le != null) ? le.value() : null, (lt != null) ? lt.value() : null);
	}

	/**
	 * Adds an error for the first bound a float breaks; NaN breaks every bound.
	 * @param value the float read
	 * @param at where it is in the input
	 * @param errors where the error is added
	 */
	void check(double value, Location at, Errors errors) {
		check(new Checked(Double.isFinite(value) ? new BigDecimal(value) : null, value, false), at, errors);
	}

	/**
	 * Adds an error for the first bound an integer breaks.
	 * @param value the integer read
	 * @param at where it is in the input
	 * @param errors where the error is added
	 */
	void check(BigDecimal value, Location at, Errors errors) {
		check(new Checked(value, value.doubleValue(), true), at, errors);
	}

	/** Checks in pydantic's order: {@code le}, {@code lt}, {@code ge}, {@code gt}. */
	private void check(Checked number, Location at, Errors errors) {
		if (this.le != null && !number.passes(this.le, (comparison) -> comparison <= 0)) {
			errors.add("less_than_equal", at, "Input should be less than or equal to " + number.bound(this.le));
		}
		else if (this.lt != null && !number.passes(this.lt, (comparison) -> comparison < 0)) {
			errors.add("less_than", at, "Input should be less than " + number.bound(this.lt));
		}
		else if (this.ge != null && !number.passes(this.ge, (comparison) -> comparison >= 0)) {
			errors.add("greater_than_equal", at, "Input should be greater than or equal to " + number.bound(this.ge));
		}
		else if (this.gt != null && !number.passes(this.gt, (comparison) -> comparison > 0)) {
			errors.add("greater_than", at, "Input should be greater than " + number.bound(this.gt));
		}
	}

	/**
	 * JSON Schema's {@code minimum}, {@code exclusiveMinimum}, {@code maximum} and
	 * {@code exclusiveMaximum}.
	 * @param schema the number's schema, which the bounds are added to
	 */
	void describe(ObjectNode schema) {
		put(schema, "minimum", this.ge);
		put(schema, "exclusiveMinimum", this.gt);
		put(schema, "maximum", this.le);
		put(schema, "exclusiveMaximum", this.lt);
	}

	private static void put(ObjectNode schema, String key, @Nullable Double bound) {
		if (bound == null) {
			return;
		}
		if (bound == Math.rint(bound) && Math.abs(bound) < INTEGRAL_BOUND_LIMIT) {
			schema.put(key, bound.longValue());
		}
		else {
			schema.put(key, bound.doubleValue());
		}
	}

	/**
	 * A number being checked against the bounds.
	 *
	 * @param exact its exact value, {@code null} for an infinity or NaN
	 * @param value its value as a double
	 * @param integer whether it was read for an integer field, whose messages write a
	 * bound as an integer
	 */
	private record Checked(@Nullable BigDecimal exact, double value, boolean integer) {

		/**
		 * Whether the number compared with {@code bound} passes {@code test}, compared
		 * exactly as Python compares an int with a float. A NaN passes no test; an
		 * infinity is beyond any bound.
		 * @param bound the bound
		 * @param test what the comparison's sign must be
		 * @return whether the number keeps to the bound
		 */
		boolean passes(double bound, IntPredicate test) {
			if (Double.isNaN(this.value)) {
				return false;
			}
			int comparison = (this.exact != null) ? this.exact.compareTo(new BigDecimal(bound))
					: ((this.value > 0) ? 1 : -1);
			return test.test(comparison);
		}

		/**
		 * A bound as this number's messages write it.
		 * @param bound the bound
		 * @return the bound's text
		 */
		String bound(double bound) {
			return Messages.bound(bound, this.integer);
		}

	}

}
