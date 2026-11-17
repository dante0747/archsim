package io.archsim.engine.report;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A run's figures as a whole: its latency, load and success, over the measured window.
 *
 * @param latencyMs the latency of the requests that succeeded, in milliseconds
 * @param latencyWithFailuresMs the latency of every request, failures included
 * @param offeredRps the requests offered per second
 * @param throughputRps the requests that succeeded per second
 * @param successRate the share of requests that succeeded
 * @param errorRate the share of requests that failed
 * @param timeoutRate the share of requests whose client gave up waiting
 * @param arrived the requests that arrived
 * @param failed the requests that failed
 * @param timedOut the requests whose client gave up waiting
 * @param attempts the attempts sent, retries included
 * @param retryAmplification the attempts per request
 * @param failureBreakdown where and why requests failed, the most first
 * @param lostMessages the messages its measured requests lost, dead-lettered or dropped,
 * when the design has a queue (report 1.19, ADR-0084); its presence says the run had one
 * @param availability the share of measured requests that succeeded and lost no message,
 * when the design has a queue
 * @param messageAgeP95Ms the p95 age of a message when processed, at the slowest queue,
 * when the design has one
 */
public record RunSummary(LatencyStats latencyMs, @Nullable LatencyStats latencyWithFailuresMs,
		@Nullable Double offeredRps, @Nullable Double throughputRps, @Nullable Double successRate,
		@Nullable Double errorRate, @Nullable Double timeoutRate, long arrived, long failed, long timedOut,
		long attempts, @Nullable Double retryAmplification, List<FailureCause> failureBreakdown,
		@Nullable Long lostMessages, @Nullable Double availability, @Nullable Double messageAgeP95Ms) {

	/**
	 * Checks that the summary has its latency and its failures, as every report's has.
	 */
	public RunSummary {
		Objects.requireNonNull(latencyMs, "a report's summary has its latency");
		failureBreakdown = ReportJson.required(failureBreakdown, "a report's summary has its failures");
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
