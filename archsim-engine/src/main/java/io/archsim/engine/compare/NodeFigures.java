package io.archsim.engine.compare;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.report.NodeSummary;

/**
 * A part's figures in one run, as a comparison shows them; each {@code null} when the run
 * did not measure it.
 *
 * @param utilization how busy its slots were
 * @param cpuUtilization how busy its CPU was
 * @param arrivalRps the requests that reached it per second
 * @param meanQueue the requests waiting for it, on average
 * @param peakQueue the most requests waiting for it at once
 * @param p95QueueWaitMs the 95th percentile of the time a request waited for it
 * @param rejectedTotal the requests it refused
 * @param hitRatio the share of reads a cache answered
 */
public record NodeFigures(@Nullable Double utilization, @Nullable Double cpuUtilization, @Nullable Double arrivalRps,
		@Nullable Double meanQueue, @Nullable Long peakQueue, @Nullable Double p95QueueWaitMs,
		@Nullable Long rejectedTotal, @Nullable Double hitRatio) {

	/**
	 * A part's figures as a run's report has them.
	 * @param summary the part's summary in the report
	 * @return its figures
	 */
	static NodeFigures of(NodeSummary summary) {
		return new NodeFigures(summary.utilization(), summary.cpuUtilization(), summary.arrivalRps(),
				summary.meanQueue(), summary.peakQueue(), summary.p95QueueWaitMs(), summary.rejectedTotal(),
				summary.hitRatio());
	}

}
