package io.archsim.engine.metrics;

import java.util.OptionalDouble;
import java.util.function.DoubleFunction;

import org.jspecify.annotations.Nullable;

/**
 * A latency distribution summed up as a report writes it, in milliseconds rounded to 4
 * decimals; every figure but the count is {@code null} when nothing was recorded.
 *
 * @param count how many latencies
 * @param mean the mean
 * @param p50 the median
 * @param p90 the 90th percentile
 * @param p95 the 95th percentile
 * @param p99 the 99th percentile
 * @param max the largest
 */
public record LatencySummary(long count, @Nullable Double mean, @Nullable Double p50, @Nullable Double p90,
		@Nullable Double p95, @Nullable Double p99, @Nullable Double max) {

	private static final double MEDIAN = 50;

	private static final double P90 = 90;

	private static final double P95 = 95;

	private static final double P99 = 99;

	/**
	 * The summary of a distribution.
	 * @param count how many latencies
	 * @param mean their mean, empty when there are none
	 * @param percentile the latency at a percentile (0 to 100), empty when there are none
	 * @param max the largest, empty when there are none
	 * @return the summary, each figure rounded
	 */
	static LatencySummary of(long count, OptionalDouble mean, DoubleFunction<OptionalDouble> percentile,
			OptionalDouble max) {
		return new LatencySummary(count, Rounding.of(mean), Rounding.of(percentile.apply(MEDIAN)),
				Rounding.of(percentile.apply(P90)), Rounding.of(percentile.apply(P95)),
				Rounding.of(percentile.apply(P99)), Rounding.of(max));
	}

}
