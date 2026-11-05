package io.archsim.engine.numeric;

/**
 * Python's division of floats, {@code a / b}: the IEEE quotient, but an error on a
 * divisor of zero, where Java gives an infinity or NaN.
 */
public final class TrueDivision {

	private TrueDivision() {
	}

	/**
	 * A quotient, as Python's {@code /} gives it for floats.
	 * @param dividend what is divided
	 * @param divisor what it is divided by
	 * @return the quotient
	 * @throws ArithmeticException when the divisor is zero, as Python raises
	 * {@code ZeroDivisionError}
	 */
	public static double of(double dividend, double divisor) {
		if (divisor == 0) {
			throw new ArithmeticException("float division by zero");
		}
		return dividend / divisor;
	}

}
