package io.archsim.benchmarks.values;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * The functions of Python's {@code statistics} and {@code math} the harness reduces its
 * results with, ported so that they give the same bits (ADR-0071):
 * {@code NormalDist().inv_cdf} (Wichura's AS241, in CPython's order of operations),
 * {@code comb}, {@code math.fsum} (Shewchuk's exact partials, as CPython keeps them) and
 * {@code fmean}, which divides {@code fsum} by the count.
 */
public final class PythonStatistics {

	/** Where AS241's central branch ends: |p - 0.5| up to this. */
	private static final double CENTRAL = 0.425;

	private static final double CENTRAL_SQUARE = 0.180625;

	/** Where AS241's intermediate branch ends: sqrt(-log(tail)) up to this. */
	private static final double INTERMEDIATE = 5.0;

	private static final double INTERMEDIATE_SHIFT = 1.6;

	private static final double HALF = 0.5;

	private static final double[] CENTRAL_NUMERATOR = { 2509.0809287301227, 33430.57558358813, 67265.7709270087,
			45921.95393154987, 13731.69376550946, 1971.5909503065513, 133.14166789178438, 3.3871328727963665 };

	private static final double[] CENTRAL_DENOMINATOR = { 5226.495278852854, 28729.085735721943, 39307.89580009271,
			21213.794301586597, 5394.196021424751, 687.1870074920579, 42.31333070160091, 1.0 };

	private static final double[] INTERMEDIATE_NUMERATOR = { 0.0007745450142783414, 0.022723844989269184,
			0.2417807251774506, 1.2704582524523684, 3.6478483247632045, 5.769497221460691, 4.630337846156546,
			1.4234371107496835 };

	private static final double[] INTERMEDIATE_DENOMINATOR = { 1.0507500716444169e-09, 0.0005475938084995345,
			0.015198666563616457, 0.14810397642748008, 0.6897673349851, 1.6763848301838038, 2.053191626637759, 1.0 };

	private static final double[] FAR_NUMERATOR = { 2.0103343992922881e-07, 2.7115555687434876e-05,
			0.0012426609473880784, 0.026532189526576124, 0.29656057182850487, 1.7848265399172913, 5.463784911164114,
			6.657904643501103 };

	private static final double[] FAR_DENOMINATOR = { 2.0442631033899397e-15, 1.421511758316446e-07,
			1.8463183175100548e-05, 0.0007868691311456133, 0.014875361290850615, 0.1369298809227358, 0.599832206555888,
			1.0 };

	private PythonStatistics() {
	}

	/**
	 * {@code NormalDist().inv_cdf(probability)}: the standard normal distribution's
	 * quantile.
	 * @param probability a probability strictly between 0 and 1
	 * @return the value below which that share of the distribution lies
	 */
	public static double normalInverse(double probability) {
		if (!(probability > 0 && probability < 1)) {
			throw new IllegalArgumentException("p must be in the range 0.0 < p < 1.0");
		}
		double centred = probability - HALF;
		if (Math.abs(centred) <= CENTRAL) {
			double square = CENTRAL_SQUARE - centred * centred;
			return horner(CENTRAL_NUMERATOR, square) * centred / horner(CENTRAL_DENOMINATOR, square);
		}
		double tail = (centred <= 0.0) ? probability : (1.0 - probability);
		double logTail = StrictMath.sqrt(-StrictMath.log(tail));
		double quantile = (logTail <= INTERMEDIATE)
				? ratio(INTERMEDIATE_NUMERATOR, INTERMEDIATE_DENOMINATOR, logTail - INTERMEDIATE_SHIFT)
				: ratio(FAR_NUMERATOR, FAR_DENOMINATOR, logTail - INTERMEDIATE);
		return (centred < 0.0) ? -quantile : quantile;
	}

	/**
	 * {@code math.comb(items, chosen)}: the ways to choose some of the items.
	 * @param items the items
	 * @param chosen the items chosen, from 0 to {@code items}
	 * @return the number of ways, exactly
	 */
	public static BigInteger comb(int items, int chosen) {
		BigInteger ways = BigInteger.ONE;
		for (int i = 0; i < chosen; i++) {
			ways = ways.multiply(BigInteger.valueOf(items - i)).divide(BigInteger.valueOf(i + 1));
		}
		return ways;
	}

	/**
	 * {@code math.fsum(values)}: the values' exact sum, rounded once (half to even).
	 * @param values the values
	 * @return the sum: 0 for none, an infinity or NaN when one is among the values
	 * @throws IllegalArgumentException when the values hold both infinities
	 * @throws ArithmeticException when finite values add up past the largest double
	 */
	public static double fsum(List<Double> values) {
		Partials partials = new Partials();
		values.forEach(partials::add);
		return partials.sum();
	}

	/**
	 * {@code statistics.fmean(values)}: their {@code fsum} over their count.
	 * @param values at least one value
	 * @return the mean
	 * @throws IllegalArgumentException when there are no values
	 */
	public static double fmean(List<Double> values) {
		if (values.isEmpty()) {
			throw new IllegalArgumentException("fmean requires at least one data point");
		}
		return fsum(values) / values.size();
	}

	private static double ratio(double[] numerator, double[] denominator, double variable) {
		return horner(numerator, variable) / horner(denominator, variable);
	}

	// ((c0 * variable + c1) * variable + c2) ... + cn, as AS241 writes it
	private static double horner(double[] coefficients, double variable) {
		double sum = coefficients[0];
		for (int i = 1; i < coefficients.length; i++) {
			sum = sum * variable + coefficients[i];
		}
		return sum;
	}

	/**
	 * Non-overlapping partials whose sum is exactly the values' so far, smallest first,
	 * and the infinities and NaNs among the values, as CPython's {@code math_fsum} keeps
	 * them.
	 */
	private static final class Partials {

		private final List<Double> exact = new ArrayList<>();

		private double special;

		private double infinite;

		void add(double value) {
			double running = value;
			int kept = 0;
			for (double partial : List.copyOf(this.exact)) {
				boolean partialLarger = Math.abs(running) < Math.abs(partial);
				double larger = partialLarger ? partial : running;
				double smaller = partialLarger ? running : partial;
				double high = larger + smaller;
				double low = smaller - (high - larger);
				if (low != 0.0) {
					this.exact.set(kept++, low);
				}
				running = high;
			}
			this.exact.subList(kept, this.exact.size()).clear();
			if (running == 0.0) {
				return;
			}
			if (Double.isFinite(running)) {
				this.exact.add(running);
				return;
			}
			addSpecial(value);
		}

		// an infinity or a NaN among the values: the partials start again after it; a
		// finite value that overflowed them is an error
		private void addSpecial(double value) {
			if (Double.isFinite(value)) {
				throw new ArithmeticException("intermediate overflow in fsum");
			}
			if (Double.isInfinite(value)) {
				this.infinite += value;
			}
			this.special += value;
			this.exact.clear();
		}

		double sum() {
			if (this.special != 0.0) {
				if (Double.isNaN(this.infinite)) {
					throw new IllegalArgumentException("-inf + inf in fsum");
				}
				return this.special;
			}
			int count = this.exact.size();
			if (count == 0) {
				return 0.0;
			}
			double high = this.exact.get(--count);
			double low = 0.0;
			while (count > 0) {
				double larger = high;
				double smaller = this.exact.get(--count);
				high = larger + smaller;
				low = smaller - (high - larger);
				if (low != 0.0) {
					break;
				}
			}
			return (count > 0) ? halfEven(high, low, this.exact.get(count - 1)) : high;
		}

		// the sum rounded half to even across the partials: when the rest below the low
		// partial has its sign, the true sum is past the halfway point, so round away
		private static double halfEven(double high, double low, double below) {
			boolean sameSign = Math.signum(low) != 0 && Math.signum(low) == Math.signum(below);
			if (!sameSign) {
				return high;
			}
			double doubled = low * 2.0;
			double rounded = high + doubled;
			return (doubled == rounded - high) ? rounded : high;
		}

	}

}
