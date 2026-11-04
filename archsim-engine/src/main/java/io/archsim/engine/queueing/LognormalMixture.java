package io.archsim.engine.queueing;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import io.archsim.engine.numeric.Erf;
import io.archsim.engine.numeric.FloatSum;

/**
 * The p95 of a mix of times, roughly: each time as a log-normal with its mean and
 * variance, the log-normals mixed by weight, and the mixture's 95th percentile found by
 * bisection. The instant estimate uses it for its p95 latency.
 */
public final class LognormalMixture {

	/** The percentile sought. */
	private static final double PERCENTILE = 0.95;

	/** The search starts this many standard deviations either side of the medians. */
	private static final double SPREAD = 6.0;

	/** Halvings of the search, in log space. */
	private static final int BISECTIONS = 80;

	private static final double SQRT_TWO = Math.sqrt(2.0);

	/** One half. */
	private static final double HALF = 0.5;

	private LognormalMixture() {
	}

	/**
	 * The mixture's p95.
	 * @param parts the times and their weights; those of no weight are left out
	 * @return the p95, in the times' unit; zero when every time is zero; empty when no
	 * part has weight
	 */
	public static OptionalDouble p95(List<WeightedTime> parts) {
		List<WeightedTime> weighted = parts.stream().filter((part) -> part.weight() > 0).toList();
		double total = FloatSum.of(weighted.stream().mapToDouble(WeightedTime::weight));
		if (weighted.isEmpty() || total <= 0) {
			return OptionalDouble.empty();
		}
		List<Fitted> fitted = new ArrayList<>();
		weighted.forEach((part) -> fitted.add(Fitted.of(part, total)));
		List<Fitted> finite = fitted.stream().filter((part) -> Double.isFinite(part.mu())).toList();
		if (finite.isEmpty()) {
			return OptionalDouble.of(0.0);
		}
		double low = Math
			.exp(finite.stream().mapToDouble((part) -> part.mu() - SPREAD * part.sigma()).min().orElseThrow());
		double high = Math
			.exp(finite.stream().mapToDouble((part) -> part.mu() + SPREAD * part.sigma()).max().orElseThrow());
		for (int i = 0; i < BISECTIONS; i++) {
			double middle = Math.sqrt(low * high);
			if (cdf(fitted, middle) < PERCENTILE) {
				low = middle;
			}
			else {
				high = middle;
			}
		}
		return OptionalDouble.of(high);
	}

	/**
	 * The mixture's cumulative distribution.
	 * @param fitted the log-normals and their shares
	 * @param time a time
	 * @return the share of the mix at or below it
	 */
	private static double cdf(List<Fitted> fitted, double time) {
		double logX = Math.log(time);
		double below = 0.0;
		for (Fitted part : fitted) {
			if (part.sigma() == 0) {
				below += (logX >= part.mu()) ? part.share() : 0.0;
			}
			else {
				below += part.share() * HALF * Erf.erfc(-(logX - part.mu()) / (part.sigma() * SQRT_TWO));
			}
		}
		return below;
	}

	/**
	 * One time as a log-normal.
	 *
	 * @param share its share of the mix
	 * @param mu the log-normal's location; minus infinity for a time that is always zero
	 * @param sigma its scale
	 */
	private record Fitted(double share, double mu, double sigma) {

		/**
		 * The log-normal with a time's mean and variance.
		 * @param part the time and its weight
		 * @param total the weights' total
		 * @return the fitted log-normal
		 */
		static Fitted of(WeightedTime part, double total) {
			Moments time = part.time();
			if (time.mean() <= 0) {
				return new Fitted(part.weight() / total, Double.NEGATIVE_INFINITY, 0.0);
			}
			double logVariance = Math.log1p(time.variance() / (time.mean() * time.mean()));
			return new Fitted(part.weight() / total, Math.log(time.mean()) - logVariance / 2, Math.sqrt(logVariance));
		}

	}

}
