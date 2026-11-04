package io.archsim.engine.numeric;

import java.util.stream.DoubleStream;

/**
 * A sum of floats with no rounding error until the one at the end: Shewchuk's algorithm,
 * correctly rounded, as Python's {@code math.fsum} computes it. A sum of thousands of
 * small terms (a harmonic number) then has the same last digits whatever their order.
 */
public final class ExactSum {

	private ExactSum() {
	}

	/**
	 * The correctly rounded sum of some floats.
	 * @param values the floats, in any order
	 * @return their sum, rounded once; with a value that is not finite, the plain sum of
	 * those values (an infinity, or NaN)
	 * @throws ArithmeticException when finite values add up to more than a float holds
	 * @throws IllegalArgumentException when the values hold both infinities
	 */
	public static double of(DoubleStream values) {
		double[] terms = values.toArray();
		double[] partials = new double[terms.length];
		int count = 0;
		double notFinite = 0;
		double infinities = 0;
		for (double term : terms) {
			if (Double.isFinite(term)) {
				count = add(term, partials, count);
				continue;
			}
			if (Double.isInfinite(term)) {
				infinities += term;
			}
			notFinite += term;
			// as Python does: the sum is now not finite, and the partials start again
			count = 0;
		}
		if (Double.isNaN(infinities)) {
			throw new IllegalArgumentException("-inf + inf in fsum");
		}
		return (notFinite != 0) ? notFinite : roundedSum(partials, count);
	}

	/**
	 * Adds a float to the partial sums: non-zero, non-overlapping floats, smallest first,
	 * whose exact sum is the exact sum so far.
	 * @param term the float to add
	 * @param partials the partial sums, updated in place
	 * @param count how many partial sums there are
	 * @return how many partial sums there are now
	 * @throws ArithmeticException when the sum is more than a float holds
	 */
	private static int add(double term, double[] partials, int count) {
		double carried = term;
		int kept = 0;
		for (int i = 0; i < count; i++) {
			double partial = partials[i];
			double larger = (Math.abs(carried) < Math.abs(partial)) ? partial : carried;
			double smaller = (Math.abs(carried) < Math.abs(partial)) ? carried : partial;
			double high = larger + smaller;
			double low = smaller - (high - larger);
			if (low != 0.0) {
				partials[kept] = low;
				kept++;
			}
			carried = high;
		}
		if (!Double.isFinite(carried)) {
			throw new ArithmeticException("intermediate overflow in fsum");
		}
		// a zero adds nothing, so a sum of negative zeros is zero
		if (carried != 0.0) {
			partials[kept] = carried;
			kept++;
		}
		return kept;
	}

	/**
	 * The partial sums added from the largest down, rounded once: a sum exactly halfway
	 * between two floats goes to the even one, as the remaining partials decide.
	 * @param partials the partial sums, smallest first
	 * @param count how many partial sums there are
	 * @return their sum
	 */
	private static double roundedSum(double[] partials, int count) {
		if (count == 0) {
			return 0.0;
		}
		int next = count - 1;
		double high = partials[next];
		double low = 0.0;
		while (next > 0) {
			double previous = high;
			next--;
			high = previous + partials[next];
			low = partials[next] - (high - previous);
			if (low != 0.0) {
				break;
			}
		}
		if (next > 0 && low != 0.0 && Math.signum(low) == Math.signum(partials[next - 1])) {
			double doubled = low * 2;
			double rounded = high + doubled;
			if (doubled == rounded - high) {
				high = rounded;
			}
		}
		return high;
	}

}
