package io.archsim.model.distribution;

import static io.archsim.model.distribution.Distribution.MAX_SAMPLE_MS;

import io.archsim.model.codec.Description;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Tag;
import io.archsim.model.text.PythonFormat;

/**
 * Right-skewed, given by its median and 95th percentile: a common, realistic shape for
 * request handling times, most near the median with a long tail.
 */
@Tag("lognormal")
@Description("""
		Right-skewed distribution parameterised by its median and 95th percentile.

		This is a common, realistic shape for request handling times: most requests are
		near the median with a long tail. ``sigma = ln(p95 / median) / 1.645``.""")
public record LogNormal(@Description("Median (p50) in ms") @Gt(0) @Le(MAX_SAMPLE_MS) double median,

		@Description("95th percentile in ms") @Gt(0) @Le(MAX_SAMPLE_MS) double p95) implements Distribution {

	/** The standard normal quantile of 0.95. */
	private static final double Z95 = 1.6448536269514722;

	public LogNormal {
		if (p95 < median) {
			throw new IllegalArgumentException("lognormal distribution requires p95 >= median");
		}
	}

	/** The mean of the underlying normal distribution: {@code ln(median)}. */
	public double mu() {
		return Math.log(median);
	}

	/**
	 * The standard deviation of the underlying normal: {@code ln(p95 / median) / 1.645}.
	 */
	public double sigma() {
		return Math.log(p95 / median) / Z95;
	}

	@Override
	public double expected() {
		return Math.exp(mu() + Math.pow(sigma(), 2) / 2.0);
	}

	@Override
	public String describe() {
		return "lognormal, p50 %s ms / p95 %s ms".formatted(PythonFormat.format(median, "g"),
				PythonFormat.format(p95, "g"));
	}
}
