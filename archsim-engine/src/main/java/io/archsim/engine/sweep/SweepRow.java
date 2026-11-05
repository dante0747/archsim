package io.archsim.engine.sweep;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.report.LatencyStats;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.RunReport;
import io.archsim.engine.report.RunSummary;
import io.archsim.model.document.Objectives;

/**
 * One step of a load sweep: the load it offered and what the run of it measured.
 *
 * @param rps the constant load the step ran, in requests per second
 * @param offered the requests offered per second, as measured
 * @param throughput the requests that succeeded per second; {@code null} until the step
 * completed
 * @param p50 the median latency, in milliseconds
 * @param p95 the p95 latency, in milliseconds
 * @param p99 the p99 latency, in milliseconds
 * @param p95WithFailures the p95 latency of every request, failures included
 * @param successRate the share of requests that succeeded
 * @param bottleneck the name of the part the run found the bottleneck, if any
 * @param resultDigest the digest of the run's result
 */
public record SweepRow(double rps, @Nullable Double offered, @Nullable Double throughput, @Nullable Double p50,
		@Nullable Double p95, @Nullable Double p99, @Nullable Double p95WithFailures, @Nullable Double successRate,
		@Nullable String bottleneck, String resultDigest) {

	/**
	 * Checks that the row names its run's result.
	 */
	public SweepRow {
		Objects.requireNonNull(resultDigest, "a sweep's row has its run's digest");
	}

	/**
	 * The row of a step's run.
	 * @param rps the load the step ran
	 * @param report the run's report
	 * @return the step's row
	 */
	public static SweepRow of(double rps, RunReport report) {
		RunSummary summary = report.summary();
		LatencyStats latency = summary.latencyMs();
		LatencyStats withFailures = summary.latencyWithFailuresMs();
		return new SweepRow(rps, summary.offeredRps(), summary.throughputRps(), latency.p50(), latency.p95(),
				latency.p99(), (withFailures != null) ? withFailures.p95() : null, summary.successRate(),
				bottleneck(report), report.provenance().resultDigest());
	}

	/**
	 * The name of the part of the run's first bottleneck finding, if the finding names a
	 * part the report has.
	 */
	private static @Nullable String bottleneck(RunReport report) {
		return report.firstFinding("bottleneck")
			.map((finding) -> finding.nodeId())
			.filter((nodeId) -> !nodeId.isEmpty())
			.flatMap(report::node)
			.map(ReportNode::label)
			.orElse(null);
	}

	/**
	 * Whether the step meets the design's latency and availability objectives (not its
	 * throughput: the step's load is set, not chosen).
	 * @param objectives the design's objectives
	 * @return whether it meets them; {@code null} until the step completed
	 */
	public @Nullable Boolean meets(Objectives objectives) {
		if (this.throughput == null) {
			return null;
		}
		return atMost(this.p95, objectives.latencyP95Ms()) && atMost(this.p99, objectives.latencyP99Ms())
				&& atLeast(this.successRate, objectives.availability());
	}

	/**
	 * Whether a latency meets its target: always without a target, never without a
	 * latency.
	 */
	private static boolean atMost(@Nullable Double actual, @Nullable Double target) {
		if (target == null) {
			return true;
		}
		return actual != null && actual <= target;
	}

	private static boolean atLeast(@Nullable Double actual, @Nullable Double target) {
		if (target == null) {
			return true;
		}
		return actual != null && actual >= target;
	}

}
