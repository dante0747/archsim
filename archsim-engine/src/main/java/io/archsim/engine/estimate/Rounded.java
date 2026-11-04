package io.archsim.engine.estimate;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.PythonRound;

/** The estimate's rounding: Python's {@code round}, and nothing for an unknown value. */
final class Rounded {

	/** Times, in milliseconds, keep this many decimals. */
	static final int TIME = 3;

	/** Shares keep this many decimals. */
	static final int SHARE = 4;

	/** Rates and the latency keep this many decimals. */
	static final int RATE = 2;

	/** The load a design saturates at keeps this many decimals. */
	static final int SATURATION = 1;

	private Rounded() {
	}

	/**
	 * A value rounded to some decimals.
	 * @param value the value; {@code null} when unknown
	 * @param digits the decimals to keep
	 * @return the rounded value; {@code null} for an unknown or infinite value
	 */
	static @Nullable Double of(@Nullable Double value, int digits) {
		return (value == null || !Double.isFinite(value)) ? null : PythonRound.round(value, digits);
	}

}
