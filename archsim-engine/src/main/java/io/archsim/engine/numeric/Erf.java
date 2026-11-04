package io.archsim.engine.numeric;

/**
 * The complementary error function, {@code erfc(x) = 1 - erf(x)}, as fdlibm computes it
 * ({@code s_erf.c}), which Python's {@code math.erfc} agrees with: a rational
 * approximation on each interval of {@code |x|}, accurate to about one unit in the last
 * place. Java has no error function of its own.
 *
 * <p>
 * The coefficients and the method are fdlibm 5.3's: Copyright (C) 1993 by Sun
 * Microsystems, Inc. All rights reserved. Developed at SunPro, a Sun Microsystems, Inc.
 * business. Permission to use, copy, modify, and distribute this software is freely
 * granted, provided that this notice is preserved.
 */
public final class Erf {

	/** Below this, {@code erfc} uses the small-argument series. */
	private static final double SMALL = 0.84375;

	/** Below this, {@code 1 - erfc} is under half an ulp of 1. */
	private static final double NEGLIGIBLE = 0x1.0p-56;

	/** Below this, the small series is written {@code 1 - (x + x·y)}. */
	private static final double QUARTER = 0.25;

	/** Below this, {@code erfc} uses the expansion around 1. */
	private static final double NEAR_ONE = 1.25;

	/** Below this, about 1/0.35, the tail uses its first approximation. */
	private static final double MIDDLE = Double.longBitsToDouble(0x4006DB6D00000000L);

	/** From here down, {@code erfc} of a negative argument is 2 to double precision. */
	private static final double NEGATIVE_LIMIT = 6.0;

	/** From here up, {@code erfc} underflows to zero. */
	private static final double UNDERFLOW = 28.0;

	/** {@code erf(1)} rounded to single precision, the expansion's centre value. */
	private static final double ERX = 0.8450629115104675;

	/** The tail's offset, {@code 0.5625 = 9/16}. */
	private static final double TAIL_OFFSET = 0.5625;

	/** One half. */
	private static final double HALF = 0.5;

	/** A number whose square underflows, for the results that round to 0 or 2. */
	private static final double TINY = 1e-300;

	/** Keeps the high 32 bits of a double. */
	private static final long HIGH_WORD = 0xFFFFFFFF00000000L;

	/** The small series' numerator, from the constant term up. */
	private static final double[] SMALL_P = { 0.12837916709551256, -0.3250421072470015, -0.02848174957559851,
			-0.005770270296489442, -2.3763016656650163e-05 };

	/** The small series' denominator. */
	private static final double[] SMALL_Q = { 1.0, 0.39791722395915535, 0.0650222499887673, 0.005081306281875766,
			0.00013249473800432164, -3.960228278775368e-06 };

	/** The expansion around 1: numerator. */
	private static final double[] NEAR_ONE_P = { -0.0023621185607526594, 0.41485611868374833, -0.3722078760357013,
			0.31834661990116175, -0.11089469428239668, 0.035478304325618236, -0.002166375594868791 };

	/** The expansion around 1: denominator. */
	private static final double[] NEAR_ONE_Q = { 1.0, 0.10642088040084423, 0.540397917702171, 0.07182865441419627,
			0.12617121980876164, 0.01363708391202905, 0.011984499846799107 };

	/** The tail up to about 1/0.35: numerator. */
	private static final double[] MIDDLE_R = { -0.009864944034847148, -0.6938585727071818, -10.558626225323291,
			-62.375332450326006, -162.39666946257347, -184.60509290671104, -81.2874355063066, -9.814329344169145 };

	/** The tail up to about 1/0.35: denominator. */
	private static final double[] MIDDLE_S = { 1.0, 19.651271667439257, 137.65775414351904, 434.56587747522923,
			645.3872717332679, 429.00814002756783, 108.63500554177944, 6.570249770319282, -0.0604244152148581 };

	/** The far tail: numerator. */
	private static final double[] TAIL_R = { -0.0098649429247001, -0.799283237680523, -17.757954917754752,
			-160.63638485582192, -637.5664433683896, -1025.0951316110772, -483.5191916086514 };

	/** The far tail: denominator. */
	private static final double[] TAIL_S = { 1.0, 30.33806074348246, 325.7925129965739, 1536.729586084437,
			3199.8582195085955, 2553.0504064331644, 474.52854120695537, -22.44095244658582 };

	private Erf() {
	}

	/**
	 * The complementary error function.
	 * @param value the argument
	 * @return {@code erfc(value)}: from 2 for large negative values down to 0 for large
	 * positive ones; NaN for NaN
	 */
	public static double erfc(double value) {
		if (Double.isNaN(value)) {
			return value;
		}
		double magnitude = Math.abs(value);
		if (magnitude < SMALL) {
			return small(value);
		}
		if (magnitude < NEAR_ONE) {
			return nearOne(value, magnitude);
		}
		if (magnitude < UNDERFLOW) {
			return tail(value, magnitude);
		}
		return (value > 0) ? TINY * TINY : 2.0 - TINY;
	}

	/**
	 * {@code erfc} for {@code |x| < 0.84375}: one minus the series for {@code erf}.
	 * @param value the argument
	 * @return {@code erfc(value)}
	 */
	private static double small(double value) {
		if (Math.abs(value) < NEGLIGIBLE) {
			return 1.0 - value;
		}
		double square = value * value;
		double ratio = polynomial(SMALL_P, square) / polynomial(SMALL_Q, square);
		if (value < QUARTER) {
			return 1.0 - (value + value * ratio);
		}
		double rest = value * ratio;
		rest += value - HALF;
		return HALF - rest;
	}

	/**
	 * {@code erfc} for {@code 0.84375 <= |x| < 1.25}: around {@code erf(1)}.
	 * @param value the argument
	 * @param magnitude its absolute value
	 * @return {@code erfc(value)}
	 */
	private static double nearOne(double value, double magnitude) {
		double offset = magnitude - 1.0;
		double ratio = polynomial(NEAR_ONE_P, offset) / polynomial(NEAR_ONE_Q, offset);
		if (value >= 0) {
			double centre = 1.0 - ERX;
			return centre - ratio;
		}
		double centre = ERX + ratio;
		return 1.0 + centre;
	}

	/**
	 * {@code erfc} for {@code 1.25 <= |x| < 28}: {@code exp(-x² - 0.5625 + R/S) / x},
	 * with {@code x²} split to keep its rounding error out.
	 * @param value the argument
	 * @param magnitude its absolute value
	 * @return {@code erfc(value)}
	 */
	private static double tail(double value, double magnitude) {
		double inverseSquare = 1.0 / (magnitude * magnitude);
		double ratio;
		if (magnitude < MIDDLE) {
			ratio = polynomial(MIDDLE_R, inverseSquare) / polynomial(MIDDLE_S, inverseSquare);
		}
		else {
			if (value < 0 && magnitude >= NEGATIVE_LIMIT) {
				return 2.0 - TINY;
			}
			ratio = polynomial(TAIL_R, inverseSquare) / polynomial(TAIL_S, inverseSquare);
		}
		double truncated = Double.longBitsToDouble(Double.doubleToRawLongBits(magnitude) & HIGH_WORD);
		double scaled = StrictMath.exp(-truncated * truncated - TAIL_OFFSET)
				* StrictMath.exp((truncated - magnitude) * (truncated + magnitude) + ratio);
		return (value > 0) ? scaled / magnitude : 2.0 - scaled / magnitude;
	}

	/**
	 * A polynomial by Horner's rule, in fdlibm's order of operations.
	 * @param coefficients its coefficients, from the constant term up
	 * @param variable the variable
	 * @return {@code c0 + v·(c1 + v·(c2 + ...))}
	 */
	private static double polynomial(double[] coefficients, double variable) {
		double value = coefficients[coefficients.length - 1];
		for (int i = coefficients.length - 2; i >= 0; i--) {
			value = coefficients[i] + variable * value;
		}
		return value;
	}

}
