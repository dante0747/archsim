package io.archsim.engine.text;

import java.math.BigDecimal;
import java.math.RoundingMode;

import io.archsim.model.text.PythonFormat;

/**
 * Rounding with halves away from zero, on a float's exact binary value: 2.5 is 3, but
 * 2.675 is 2.67 at two decimals, because the float nearest 2.675 is just below it. For
 * positive values this is how the web app's JavaScript rounds its figures; a negative
 * half goes away from zero here ({@code -2.5} is {@code -3}), as the Python engine rounds
 * it. A negative value that rounds to zero keeps its sign, as Python's {@code Decimal}
 * keeps it.
 */
public final class HalfUp {

	private static final double NEGATIVE_ZERO = -0.0;

	private static final double HALF = 0.5;

	private HalfUp() {
	}

	/**
	 * A float rounded to some decimals.
	 * @param value a finite float
	 * @param digits how many decimals to keep
	 * @return the float nearest the rounded value
	 * @throws NumberFormatException when the value is not finite
	 */
	public static double round(double value, int digits) {
		double rounded = exact(value, digits).doubleValue();
		return (rounded == 0 && isNegative(value)) ? NEGATIVE_ZERO : rounded;
	}

	/**
	 * A float rounded to some decimals and written with exactly that many, as
	 * JavaScript's {@code Number.toFixed} writes it: {@code fixed(1, 2)} is {@code 1.00}.
	 * @param value a finite float
	 * @param digits how many decimals to write
	 * @return its text
	 * @throws NumberFormatException when the value is not finite
	 */
	public static String fixed(double value, int digits) {
		BigDecimal rounded = exact(value, digits);
		String text = rounded.toPlainString();
		return (rounded.signum() == 0 && isNegative(value)) ? "-" + text : text;
	}

	/**
	 * A float rounded to a whole number and written with thousands separators:
	 * {@code 1,235}. A whole number has no sign when it is zero.
	 * @param value a finite float
	 * @return its text
	 * @throws NumberFormatException when the value is not finite
	 */
	public static String grouped(double value) {
		return PythonFormat.format(exact(value, 0).doubleValue(), ",.0f");
	}

	/**
	 * JavaScript's {@code Math.round}, as the Python engine computes it:
	 * {@code floor(value + 0.5)}, halves towards positive infinity. Not Java's
	 * {@code Math.round}: the two differ at {@code 0.49999999999999994}, where adding a
	 * half rounds up to 1 before the floor is taken.
	 * @param value the float
	 * @return the nearest whole number, as a float
	 */
	public static double javaScriptRound(double value) {
		return Math.floor(value + HALF);
	}

	private static BigDecimal exact(double value, int digits) {
		return new BigDecimal(value).setScale(digits, RoundingMode.HALF_UP);
	}

	private static boolean isNegative(double value) {
		return Math.copySign(1.0, value) < 0;
	}

}
