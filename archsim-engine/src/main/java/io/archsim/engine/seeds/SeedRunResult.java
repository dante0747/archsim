package io.archsim.engine.seeds;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.slo.RunFigures;

/**
 * A finished run of a seed group, judged against the design's objectives.
 *
 * @param seed the run's seed
 * @param pass whether it meets every objective; {@code null} when that cannot be decided
 * @param p50 its median latency, in milliseconds
 * @param p95 its p95 latency, in milliseconds
 * @param p99 its p99 latency, in milliseconds
 * @param successRate the share of its requests that succeeded
 * @param throughputRps its successful requests per second
 */
public record SeedRunResult(long seed, @Nullable Boolean pass, @Nullable Double p50, @Nullable Double p95,
		@Nullable Double p99, @Nullable Double successRate, @Nullable Double throughputRps) {

	/**
	 * A run's verdict beside its figures.
	 * @param seed the run's seed
	 * @param pass its verdict
	 * @param figures its figures
	 * @return the run's result
	 */
	static SeedRunResult of(long seed, @Nullable Boolean pass, RunFigures figures) {
		return new SeedRunResult(seed, pass, figures.p50(), figures.p95(), figures.p99(), figures.successRate(),
				figures.throughputRps());
	}

}
