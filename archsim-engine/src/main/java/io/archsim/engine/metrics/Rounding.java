package io.archsim.engine.metrics;

import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.PythonRound;

/**
 * How the metrics write a figure: rounded to 4 decimals as Python's {@code round} rounds,
 * and none where it is missing or not finite.
 */
final class Rounding {

	/** The decimals a figure keeps. */
	private static final int DIGITS = 4;

	private Rounding() {
	}

	/**
	 * A figure rounded.
	 * @param value the figure
	 * @return it rounded to 4 decimals, or {@code null} when not a number or infinite
	 */
	static @Nullable Double of(double value) {
		return Double.isFinite(value) ? PythonRound.round(value, DIGITS) : null;
	}

	/**
	 * A figure rounded, if there is one.
	 * @param value the figure
	 * @return it rounded to 4 decimals, or {@code null} when missing, not a number or
	 * infinite
	 */
	static @Nullable Double of(OptionalDouble value) {
		return value.isPresent() ? of(value.getAsDouble()) : null;
	}

}
