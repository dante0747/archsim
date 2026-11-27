package io.archsim.model.distribution;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Tag;
import io.archsim.model.text.PythonFormat;

/**
 * Right-skewed, given by its median and 95th percentile: a common, realistic shape for
 * request handling times, most near the median with a long tail.
 *
 * @param median the median (p50), in milliseconds
 * @param p95 the 95th percentile, in milliseconds, at least the median
 */
@Tag("lognormal")
@Description("""
		Right-skewed distribution parameterised by its median and 95th percentile.

		This is a common, realistic shape for request handling times: most requests are
		near the median with a long tail. ``sigma = ln(p95 / median) / 1.645``.""")
public record LogNormal(@Description("Median (p50) in ms") @Gt(0) @Le(MAX_SAMPLE_MS) double median,

		@Description("95th percentile in ms") @Gt(0) @Le(MAX_SAMPLE_MS) double p95) implements Distribution {

	/**
	 * The standard normal quantile of 0.95, which ties a lognormal's p95 to its median.
	 */
	public static final double Z95 = 1.6448536269514722;

	/**
	 * Checks the p95 is at least the median; the comparison is written so that NaN fails
	 * it.
	 */
	public LogNormal {
		if (!(p95 >= median)) {
			throw new IllegalArgumentException("lognormal distribution requires p95 >= median");
		}
	}

	/**
	 * The mean of the underlying normal distribution: {@code ln(median)}, with
	 * {@link StrictMath}'s logarithm, so a simulation samples alike on every platform.
	 */
	public double mu() {
		return StrictMath.log(this.median);
	}

	/**
	 * The standard deviation of the underlying normal: {@code ln(p95 / median) / 1.645},
	 * with {@link StrictMath}'s logarithm.
	 */
	public double sigma() {
		return StrictMath.log(this.p95 / this.median) / Z95;
	}

	@Override
	public double expected() {
		return StrictMath.exp(mu() + StrictMath.pow(sigma(), 2) / 2.0);
	}

	@Override
	public String describe() {
		return "lognormal, p50 %s ms / p95 %s ms".formatted(PythonFormat.format(this.median, "g"),
				PythonFormat.format(this.p95, "g"));
	}
}
