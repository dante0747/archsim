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
 * @param availability the share of requests that succeeded and whose messages were not
 * lost (ADR-0084): the success rate, for a design without a queue
 * @param messageAgeP95Ms the p95 age of a message when processed, at the slowest queue;
 * {@code null} without a queue, or when a queue held messages it never processed
 * @param queued whether the run's design has a queue, so the message age is judged
 */
public record RunFigures(@Nullable Double p50, @Nullable Double p95, @Nullable Double p99, @Nullable Double successRate,
		@Nullable Double throughputRps, @Nullable Double availability, @Nullable Double messageAgeP95Ms,
		boolean queued) {

	/**
	 * The figures of a report's summary.
	 * @param summary the summary
	 * @return its figures
	 */
	public static RunFigures of(RunSummary summary) {
		return new RunFigures(summary.latencyMs().p50(), summary.latencyMs().p95(), summary.latencyMs().p99(),
				summary.successRate(), summary.throughputRps(),
				availability(summary.availability(), summary.successRate()), summary.messageAgeP95Ms(),
				summary.lostMessages() != null);
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
		Double successRate = StoredNumbers.number(summary, "successRate");
		return new RunFigures(StoredNumbers.number(summary, "p50"), StoredNumbers.number(summary, "p95"),
				StoredNumbers.number(summary, "p99"), successRate, StoredNumbers.number(summary, "throughputRps"),
				availability(StoredNumbers.number(summary, "availability"), successRate),
				StoredNumbers.number(summary, "messageAgeP95Ms"), summary.has("lostMessages"));
	}

	// a design with a queue's availability, counting its lost messages; else its success
	// rate (ADR-0084)
	private static @Nullable Double availability(@Nullable Double availability, @Nullable Double successRate) {
		return (availability != null) ? availability : successRate;
	}

}
