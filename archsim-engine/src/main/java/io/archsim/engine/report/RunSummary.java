package io.archsim.engine.report;

import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A run's figures as a whole: its latency, load and success.
 *
 * @param latencyMs the latency of the requests that succeeded, in milliseconds
 * @param latencyWithFailuresMs the latency of every request, failures included
 * @param offeredRps the requests offered per second
 * @param throughputRps the requests that succeeded per second
 * @param successRate the share of requests that succeeded
 */
public record RunSummary(LatencyStats latencyMs, @Nullable LatencyStats latencyWithFailuresMs,
		@Nullable Double offeredRps, @Nullable Double throughputRps, @Nullable Double successRate) {

	/**
	 * Checks that the summary has its latency, as every report's has.
	 */
	public RunSummary {
		Objects.requireNonNull(latencyMs, "a report's summary has its latency");
	}

	/**
	 * Reads a report's summary.
	 * @param json the summary
	 * @return what the engine reads of it
	 * @throws tools.jackson.core.JacksonException when the JSON is not a report's summary
	 */
	public static RunSummary read(JsonNode json) {
		return ReportJson.read(json, RunSummary.class);
	}

}
