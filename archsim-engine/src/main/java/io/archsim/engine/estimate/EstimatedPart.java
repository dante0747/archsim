package io.archsim.engine.estimate;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.OmitWhenDefault;

/**
 * A part's estimate at the peak. Figures are rounded as the Python engine rounds them:
 * times to three decimals, shares to four.
 *
 * @param id its id
 * @param label its label
 * @param type its component type
 * @param onPath whether it is on a client's request path
 * @param arrivalRps the requests reaching it per second
 * @param status ok, busy or overloaded
 * @param slots its slots per pool (workers, connections, executors, consumers);
 * {@code null} when it has no limit
 * @param pools its pools: replicas, instances or shards
 * @param meanServiceMs its own time per request
 * @param meanHoldMs how long a request holds a slot, its own calls included when it waits
 * on them
 * @param utilization the share of its slots or CPU in use, the larger
 * @param slotUtilization the share of its slots in use
 * @param cpuUtilization the share of its CPU in use
 * @param rateLimitShare the share of a rate or throughput limit used
 * @param load the largest of those three shares, the CPU's on a part's own hyperthread
 * pairs at least the share of its vCPUs' capacity at the sibling speed (ADR-0091)
 * @param limitedBy which of them is the largest
 * @param waitProbability the chance a request waits for a slot; {@code null} when
 * overloaded
 * @param meanWaitMs the mean wait for a slot; {@code null} when overloaded
 * @param meanResidenceMs how long a request stays in the part, its own calls included;
 * left out when unknown
 * @param perRequestMs its time per client request, its wait and own time; left out when
 * the latency is not estimated
 */
public record EstimatedPart(String id, String label, String type, boolean onPath, @Nullable Double arrivalRps,
		PartStatus status, @Nullable Integer slots, int pools, @Nullable Double meanServiceMs,
		@Nullable Double meanHoldMs, @Nullable Double utilization, @Nullable Double slotUtilization,
		@Nullable Double cpuUtilization, @Nullable Double rateLimitShare, @Nullable Double load,
		@Nullable Limit limitedBy, @Nullable Double waitProbability, @Nullable Double meanWaitMs,
		@OmitWhenDefault @Nullable Double meanResidenceMs,
		@OmitWhenDefault @Nullable Double perRequestMs) implements PartEstimate {

	/**
	 * This estimate with how long a request stays in the part.
	 * @param ms the mean, rounded
	 * @return the estimate with it
	 */
	EstimatedPart withMeanResidenceMs(@Nullable Double ms) {
		return new EstimatedPart(this.id, this.label, this.type, this.onPath, this.arrivalRps, this.status, this.slots,
				this.pools, this.meanServiceMs, this.meanHoldMs, this.utilization, this.slotUtilization,
				this.cpuUtilization, this.rateLimitShare, this.load, this.limitedBy, this.waitProbability,
				this.meanWaitMs, ms, this.perRequestMs);
	}

	/**
	 * This estimate with the part's time per client request.
	 * @param ms its wait and own time per client request, rounded
	 * @return the estimate with it
	 */
	EstimatedPart withPerRequestMs(@Nullable Double ms) {
		return new EstimatedPart(this.id, this.label, this.type, this.onPath, this.arrivalRps, this.status, this.slots,
				this.pools, this.meanServiceMs, this.meanHoldMs, this.utilization, this.slotUtilization,
				this.cpuUtilization, this.rateLimitShare, this.load, this.limitedBy, this.waitProbability,
				this.meanWaitMs, this.meanResidenceMs, ms);
	}

}
