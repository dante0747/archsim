package io.archsim.engine.review;

/**
 * The review's arithmetic on settings read as floats, as Python works it: {@code int()}
 * truncates toward zero, and {@code max} and {@code min} keep the first of two equal
 * values.
 */
final class ReviewNumbers {

	private ReviewNumbers() {
	}

	/**
	 * The whole part of a float, toward zero.
	 * @param value a finite float
	 * @return its whole part, as a float
	 */
	static double whole(double value) {
		return (value < 0) ? Math.ceil(value) : Math.floor(value);
	}

	/**
	 * The larger of two floats: the first, unless the second is larger.
	 * @param first a float
	 * @param second another
	 * @return the larger, the first of two equal
	 */
	static double larger(double first, double second) {
		return (second > first) ? second : first;
	}

	/**
	 * The smaller of two floats: the first, unless the second is smaller.
	 * @param first a float
	 * @param second another
	 * @return the smaller, the first of two equal
	 */
	static double smaller(double first, double second) {
		return (second < first) ? second : first;
	}

}
