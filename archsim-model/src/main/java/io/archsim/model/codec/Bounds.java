package io.archsim.model.codec;

import java.lang.reflect.AnnotatedType;
import java.math.BigDecimal;
import java.util.function.IntPredicate;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * The bounds of a number ({@link Ge}, {@link Gt}, {@link Le}, {@link Lt}), checked as
 * pydantic does.
 */
record Bounds(@Nullable Double ge, @Nullable Double gt, @Nullable Double le, @Nullable Double lt) {

	static Bounds of(AnnotatedType type) {
		Ge ge = type.getAnnotation(Ge.class);
		Gt gt = type.getAnnotation(Gt.class);
		Le le = type.getAnnotation(Le.class);
		Lt lt = type.getAnnotation(Lt.class);
		return new Bounds(ge == null ? null : ge.value(), gt == null ? null : gt.value(),
				le == null ? null : le.value(), lt == null ? null : lt.value());
	}

	/** Adds an error for the first bound a float breaks; NaN breaks every bound. */
	void check(double value, Location at, Errors errors) {
		check(Double.isFinite(value) ? new BigDecimal(value) : null, value, at, errors, false);
	}

	/** Adds an error for the first bound an integer breaks. */
	void check(BigDecimal value, Location at, Errors errors) {
		check(value, value.doubleValue(), at, errors, true);
	}

	/** Checks in pydantic's order: {@code le}, {@code lt}, {@code ge}, {@code gt}. */
	private void check(@Nullable BigDecimal exact, double value, Location at, Errors errors, boolean integer) {
		if (le != null && !holds(exact, value, le, c -> c <= 0)) {
			errors.add("less_than_equal", at, "Input should be less than or equal to " + Messages.bound(le, integer));
		}
		else if (lt != null && !holds(exact, value, lt, c -> c < 0)) {
			errors.add("less_than", at, "Input should be less than " + Messages.bound(lt, integer));
		}
		else if (ge != null && !holds(exact, value, ge, c -> c >= 0)) {
			errors.add("greater_than_equal", at,
					"Input should be greater than or equal to " + Messages.bound(ge, integer));
		}
		else if (gt != null && !holds(exact, value, gt, c -> c > 0)) {
			errors.add("greater_than", at, "Input should be greater than " + Messages.bound(gt, integer));
		}
	}

	/**
	 * JSON Schema's {@code minimum}, {@code exclusiveMinimum}, {@code maximum} and
	 * {@code exclusiveMaximum}.
	 */
	void describe(ObjectNode schema) {
		put(schema, "minimum", ge);
		put(schema, "exclusiveMinimum", gt);
		put(schema, "maximum", le);
		put(schema, "exclusiveMaximum", lt);
	}

	/**
	 * Whether {@code value} compared with {@code bound} passes {@code test}, compared
	 * exactly as Python compares an int with a float. A NaN passes no test; an infinity
	 * is beyond any bound.
	 */
	private static boolean holds(@Nullable BigDecimal exact, double value, double bound, IntPredicate test) {
		if (Double.isNaN(value)) {
			return false;
		}
		int comparison = exact == null ? (value > 0 ? 1 : -1) : exact.compareTo(new BigDecimal(bound));
		return test.test(comparison);
	}

	private static void put(ObjectNode schema, String key, @Nullable Double bound) {
		if (bound == null) {
			return;
		}
		if (bound == Math.rint(bound) && Math.abs(bound) < 1e15) {
			schema.put(key, bound.longValue());
		}
		else {
			schema.put(key, bound.doubleValue());
		}
	}
}
