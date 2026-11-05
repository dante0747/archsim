package io.archsim.engine.slo;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.report.RunSummary;

/**
 * The figures a run is judged by and a group of runs is summarized from; {@code null}
 * where the run has none.
 *
 * @param p50 the median latency, in milliseconds
 * @param p95 the p95 latency, in milliseconds
 * @param p99 the p99 latency, in milliseconds
 * @param successRate the share of requests that succeeded
 * @param throughputRps the requests that succeeded per second
 */
public record RunFigures(@Nullable Double p50, @Nullable Double p95, @Nullable Double p99, @Nullable Double successRate,
		@Nullable Double throughputRps) {

	/**
	 * The figures of a report's summary.
	 * @param summary the summary
	 * @return its figures
	 */
	public static RunFigures of(RunSummary summary) {
		return new RunFigures(summary.latencyMs().p50(), summary.latencyMs().p95(), summary.latencyMs().p99(),
				summary.successRate(), summary.throughputRps());
	}

	/**
	 * The figures of a run's summary in either shape: a report's, with its latencies
	 * under {@code latencyMs}, or a run's compact summary, with {@code p50}, {@code p95}
	 * and {@code p99} beside its other figures, as the workers store it for listings.
	 * @param summary the summary
	 * @return its figures
	 * @throws tools.jackson.core.JacksonException when a report's summary is not one
	 */
	public static RunFigures read(JsonNode summary) {
		if (summary.path("latencyMs").isObject()) {
			return of(RunSummary.read(summary));
		}
		return new RunFigures(StoredNumbers.number(summary, "p50"), StoredNumbers.number(summary, "p95"),
				StoredNumbers.number(summary, "p99"), StoredNumbers.number(summary, "successRate"),
				StoredNumbers.number(summary, "throughputRps"));
	}

}
