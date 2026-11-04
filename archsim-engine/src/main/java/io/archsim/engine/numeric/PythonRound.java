package io.archsim.engine.numeric;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Python's {@code round(x, digits)} of a float: the float's exact binary value rounded to
 * that many decimals, halves to even, so {@code round(2.675, 2)} is {@code 2.67}.
 */
public final class PythonRound {

	private PythonRound() {
	}

	/**
	 * A float rounded to some decimals.
	 * @param value the float
	 * @param digits how many decimals to keep
	 * @return the float nearest the rounded value; a value that is not finite as it is
	 */
	public static double round(double value, int digits) {
		if (!Double.isFinite(value)) {
			return value;
		}
		double rounded = new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
		// a negative value that rounds to zero keeps its sign, as Python's does
		return (rounded == 0) ? Math.copySign(0.0, value) : rounded;
	}

}
