package io.archsim.engine.queueing;

import java.util.List;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.FloatSum;
import io.archsim.model.distribution.Constant;
import io.archsim.model.distribution.Distribution;
import io.archsim.model.distribution.Empirical;
import io.archsim.model.distribution.Exponential;
import io.archsim.model.distribution.LogNormal;
import io.archsim.model.distribution.Quantile;
import io.archsim.model.distribution.Uniform;

/**
 * The first two moments of a time.
 *
 * @param mean the mean, in milliseconds
 * @param variance the variance, in square milliseconds
 */
public record Moments(double mean, double variance) {

	/** A time that is always zero. */
	public static final Moments ZERO = new Moments(0.0, 0.0);

	/** Below this, {@link #times} takes a whole number of calls for sure. */
	private static final double WHOLE_TOLERANCE = 1e-12;

	/** The square of a linear stretch integrates to its ends' terms over this. */
	private static final double LINEAR_SQUARE_DIVISOR = 3.0;

	/** A uniform distribution's variance is its width squared over this. */
	private static final double UNIFORM_VARIANCE_DIVISOR = 12.0;

	/**
	 * The mean and variance of a distribution.
	 * @param distribution a distribution of times, in milliseconds; {@code null} for none
	 * @return its moments; zero for none
	 */
	public static Moments of(@Nullable Distribution distribution) {
		return switch (distribution) {
			case null -> ZERO;
			case Constant constant -> new Moments(constant.value(), 0.0);
			case Exponential exponential -> new Moments(exponential.mean(), exponential.mean() * exponential.mean());
			case Uniform uniform -> new Moments((uniform.min() + uniform.max()) / 2,
					(uniform.max() - uniform.min()) * (uniform.max() - uniform.min()) / UNIFORM_VARIANCE_DIVISOR);
			case LogNormal logNormal -> logNormal(logNormal);
			case Empirical empirical -> empirical(empirical);
		};
	}

	/**
	 * Two times one after the other.
	 * @param other the time after this one
	 * @return the moments of their sum
	 */
	public Moments plus(Moments other) {
		return new Moments(this.mean + other.mean, this.variance + other.variance);
	}

	/**
	 * This time taken {@code count} times on average: the whole calls for sure, the
	 * fraction with that probability (a cache miss, a load balancer's share).
	 * @param count how many times on average
	 * @return the moments of the total; zero for no times
	 */
	public Moments times(double count) {
		if (count <= 0) {
			return ZERO;
		}
		double whole = Math.floor(count + WHOLE_TOLERANCE);
		double fraction = Math.max(0.0, count - whole);
		double varianceOfTotal = whole * this.variance + fraction * (this.variance + this.mean * this.mean)
				- (fraction * this.mean) * (fraction * this.mean);
		return new Moments(count * this.mean, Math.max(0.0, varianceOfTotal));
	}

	/**
	 * This time scaled by a factor: a part's work at a speed or a load.
	 * @param factor how many times as long
	 * @return the scaled moments
	 */
	public Moments scaledBy(double factor) {
		return new Moments(this.mean * factor, this.variance * factor * factor);
	}

	private static Moments logNormal(LogNormal distribution) {
		double mean = distribution.expected();
		return new Moments(mean, mean * mean * StrictMath.expm1(distribution.sigma() * distribution.sigma()));
	}

	/**
	 * An empirical distribution's moments, linear between its quantiles.
	 * @param distribution the distribution
	 * @return its mean and variance
	 */
	private static Moments empirical(Empirical distribution) {
		List<Quantile> quantiles = distribution.quantiles();
		double second = FloatSum.of(IntStream.range(1, quantiles.size())
			.mapToDouble((index) -> secondMomentBetween(quantiles.get(index - 1), quantiles.get(index))));
		double mean = distribution.expected();
		return new Moments(mean, Math.max(0.0, second - mean * mean));
	}

	/**
	 * The share of the second moment between two quantiles, the time linear between them.
	 * @param low the lower quantile
	 * @param high the next one
	 * @return {@code (p1 - p0)(v0² + v0·v1 + v1²) / 3}
	 */
	private static double secondMomentBetween(Quantile low, Quantile high) {
		return (high.p() - low.p()) * (low.ms() * low.ms() + low.ms() * high.ms() + high.ms() * high.ms())
				/ LINEAR_SQUARE_DIVISOR;
	}

}
