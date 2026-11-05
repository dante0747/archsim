package io.archsim.engine.report;

import org.jspecify.annotations.Nullable;

/**
 * A part's figures over a run's measured window; {@code null} where its kind has no such
 * figure or it measured nothing.
 *
 * @param arrivals the requests that arrived
 * @param arrivalRps the requests that arrived per second
 * @param slots how many requests it serves at once (workers, connections, executors)
 * @param meanHoldMs how long a request keeps its slot on average, in milliseconds
 * @param utilization the share of slot-time busy
 * @param meanInSystem the requests inside it on average
 * @param latencyMs its latency
 * @param meanServiceMs its service time on average, in milliseconds
 * @param meanQueueWaitMs the wait for a slot on average, in milliseconds
 * @param cpuCapacityRps the requests per second its CPU can serve
 */
public record NodeSummary(@Nullable Long arrivals, @Nullable Double arrivalRps, @Nullable Double slots,
		@Nullable Double meanHoldMs, @Nullable Double utilization, @Nullable Double meanInSystem,
		@Nullable LatencyStats latencyMs, @Nullable Double meanServiceMs, @Nullable Double meanQueueWaitMs,
		@Nullable Double cpuCapacityRps) {

}
