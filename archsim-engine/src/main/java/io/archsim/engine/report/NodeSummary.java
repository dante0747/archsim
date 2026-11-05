package io.archsim.engine.report;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * A part's figures over a run's measured window, as flat as the report writes them;
 * {@code null} where its kind has no such figure or it measured nothing.
 *
 * @param arrivals the requests that arrived
 * @param arrivalRps the requests that arrived per second
 * @param completed the requests it completed
 * @param slots how many requests it serves at once (workers, connections, executors); a
 * time average for a part that scales
 * @param meanHoldMs how long a request keeps its slot on average, in milliseconds
 * @param utilization the share of slot-time busy
 * @param peakUtilization the share of slot-time busy in its busiest time bucket
 * @param cpuUtilization the share of busy slot-time spent on its own processing
 * @param cpuCoreUtilization the share of its CPU cores busy
 * @param meanInSystem the requests inside it on average
 * @param meanQueue the requests waiting for a slot on average
 * @param peakQueue the most requests waiting for a slot at once
 * @param latencyMs its latency
 * @param meanServiceMs its service time on average, in milliseconds
 * @param meanQueueWaitMs the wait for a slot on average, in milliseconds
 * @param cpuCapacityRps the requests per second its CPU can serve
 * @param capacityRps the requests per second it can sustain
 * @param capacityLimit what limits that capacity, such as {@code cpu} or {@code memory}
 * @param rejected the requests it rejected, by reason, in the report's order
 * @param rejectedTotal the requests it rejected
 * @param counters the counts its kind keeps, by name, in the report's order
 * @param hitRatio a cache's share of reads served from it
 * @param podsPendingEnd a cluster's pods still Pending at the end
 * @param podsPendingPeak the most of a cluster's pods Pending at once
 * @param cpuRequestedRatio the share of a cluster's node CPU its pods request
 * @param maxPendingS the longest a cluster's pod was Pending, in seconds
 * @param maxReplicationLagMs the furthest a data store's replicas fell behind, in
 * milliseconds
 * @param meanLockWaitMs a data store's wait for row locks on average, in milliseconds
 * @param replicasPeak the most instances it ran at once
 * @param coldStartsTotal a serverless function's cold starts in the whole run
 * @param backlogAtEnd the messages a queue still held at the end
 * @param p95QueueWaitMs the p95 wait before a consumer took a message, in milliseconds
 * @param p95MessageAgeMs the p95 age of a message when processed, in milliseconds
 */
public record NodeSummary(@Nullable Long arrivals, @Nullable Double arrivalRps, @Nullable Long completed,
		@Nullable Double slots, @Nullable Double meanHoldMs, @Nullable Double utilization,
		@Nullable Double peakUtilization, @Nullable Double cpuUtilization, @Nullable Double cpuCoreUtilization,
		@Nullable Double meanInSystem, @Nullable Double meanQueue, @Nullable Long peakQueue,
		@Nullable LatencyStats latencyMs, @Nullable Double meanServiceMs, @Nullable Double meanQueueWaitMs,
		@Nullable Double cpuCapacityRps, @Nullable Double capacityRps, @Nullable String capacityLimit,
		Map<String, Long> rejected, @Nullable Long rejectedTotal, Map<String, Long> counters, @Nullable Double hitRatio,
		@Nullable Long podsPendingEnd, @Nullable Long podsPendingPeak, @Nullable Double cpuRequestedRatio,
		@Nullable Double maxPendingS, @Nullable Double maxReplicationLagMs, @Nullable Double meanLockWaitMs,
		@Nullable Long replicasPeak, @Nullable Long coldStartsTotal, @Nullable Long backlogAtEnd,
		@Nullable Double p95QueueWaitMs, @Nullable Double p95MessageAgeMs) {

	/**
	 * Copies the counts by reason and by name; a part without them has none.
	 */
	public NodeSummary {
		rejected = ReportJson.countsByName(rejected);
		counters = ReportJson.countsByName(counters);
	}

	/**
	 * A count its kind keeps.
	 * @param name such as {@code misses}
	 * @return the count; zero when the part keeps no such count
	 */
	public long counter(String name) {
		return this.counters.getOrDefault(name, 0L);
	}

	/**
	 * The requests it rejected for a reason.
	 * @param reason such as {@code throttled}
	 * @return how many; zero when none were
	 */
	public long rejectedFor(String reason) {
		return this.rejected.getOrDefault(reason, 0L);
	}

}
