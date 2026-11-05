package io.archsim.engine.numeric;

/**
 * Python's floor division of floats, {@code a // b}. It is not {@code floor(a / b)}: the
 * quotient is worked out from the exact remainder, so {@code 1 // 0.1} is 9, where
 * {@code 1 / 0.1} rounds to 10. CPython's {@code _float_div_mod}, step by step.
 */
public final class FloorDivision {

	private static final double HALF = 0.5;

	private FloorDivision() {
	}

	/**
	 * The floor of a quotient, as Python's {@code //} gives it for floats.
	 * @param dividend what is divided
	 * @param divisor what it is divided by
	 * @return the quotient rounded down; a zero has the sign of the true quotient
	 * @throws ArithmeticException when the divisor is zero, as Python raises
	 * {@code ZeroDivisionError}
	 */
	public static double of(double dividend, double divisor) {
		if (divisor == 0) {
			throw new ArithmeticException("float floor division by zero");
		}
		// Java's % on doubles is C's fmod: exact, with the dividend's sign
		double remainder = dividend % divisor;
		double quotient = (dividend - remainder) / divisor;
		if (remainder != 0 && (divisor < 0) != (remainder < 0)) {
			quotient -= 1.0;
		}
		if (quotient == 0) {
			return Math.copySign(0.0, dividend / divisor);
		}
		double floor = Math.floor(quotient);
		return (quotient - floor > HALF) ? floor + 1.0 : floor;
	}

}
