package io.archsim.engine.numeric;

import java.util.stream.DoubleStream;

/**
 * A sum of floats as Python's built-in {@code sum()} adds them since Python 3.12: from
 * zero, in order, with Neumaier's compensation for the rounding error of each addition,
 * added back once at the end. Cheaper than {@link ExactSum}, and not always correctly
 * rounded.
 */
public final class FloatSum {

	private FloatSum() {
	}

	/**
	 * The compensated sum of some floats.
	 * @param values the floats, in the order Python would add them
	 * @return their sum
	 */
	public static double of(DoubleStream values) {
		double[] terms = values.toArray();
		double sum = 0.0;
		double compensation = 0.0;
		for (double term : terms) {
			double next = sum + term;
			compensation += (Math.abs(sum) >= Math.abs(term)) ? (sum - next) + term : (term - next) + sum;
			sum = next;
		}
		// an infinite sum stays infinite: its compensation is not a number
		return (compensation != 0.0 && Double.isFinite(compensation)) ? sum + compensation : sum;
	}

}
