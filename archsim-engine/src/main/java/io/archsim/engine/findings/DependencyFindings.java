package io.archsim.engine.findings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.report.FailureCause;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.RunReport;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.external.ExternalApiConfig;
import io.archsim.model.component.kubernetes.ClusterConfig;
import io.archsim.model.component.loadbalancer.LoadBalancerConfig;
import io.archsim.model.component.service.ServiceConfig;

/**
 * Findings about what a design depends on: a third party's errors and latency, limits
 * that turned requests away, and a cluster's pods waiting for room on a node.
 */
final class DependencyFindings {

	/** From this share of calls failed, a third party's errors matter. */
	private static final double FAILS_OFTEN = 0.01;

	/**
	 * A third party's call this long against the median request dominates its latency.
	 */
	private static final double DOMINANT_CALL = 0.5;

	/** ... when at least this share of the requests call it. */
	private static final double CALLED_BY = 0.2;

	/** Above this share turned away, throttling matters... */
	private static final double THROTTLED = 0.01;

	/** ... and above this share, it is critical. */
	private static final double MOSTLY_THROTTLED = 0.1;

	/** A pod waiting this long, in seconds, is worth a warning. */
	private static final double LONG_WAIT_S = 30;

	/** The decimals a time is cited with. */
	private static final int TIME_DECIMALS = 3;

	private static final String CALL_ASYNCHRONOUSLY = "If the user does not need the result right away, call it "
			+ "asynchronously through a queue; otherwise cache responses and "
			+ "keep its timeout well below the client's.";

	private static final String RETRY_PROVIDER = "Retry idempotent calls with exponential backoff and jitter (max "
			+ "attempts on the link), set a timeout, and add a fallback or "
			+ "circuit breaker so provider errors do not fail whole requests.";

	private static final String RAISE_THROUGHPUT = "Raise the provisioned throughput (or use on-demand capacity), "
			+ "cache hot reads in front of the store, or spread the load over more partitions with an even key.";

	private static final String STAY_UNDER_LIMIT = "Stay under the limit: queue or batch the calls, cache responses, "
			+ "retry with exponential backoff and jitter, or ask for a higher limit.";

	private static final String KEEP_HEADROOM = "Keep headroom (more nodes, or a lower HPA target), use "
			+ "faster-provisioning nodes or smaller pod requests, or raise the maximum node count.";

	private DependencyFindings() {
	}

	/**
	 * A third party's errors, and its share of the request latency.
	 * @param node the third party
	 * @param report the run's report
	 * @return the findings, for a third party that took enough calls
	 */
	static List<RunFinding> thirdParty(ReportNode node, RunReport report) {
		List<RunFinding> findings = new ArrayList<>();
		NodeSummary summary = node.summary();
		long arrivals = Objects.requireNonNullElse(summary.arrivals(), 0L);
		if (arrivals < DataFindings.MIN_SAMPLES) {
			return findings;
		}
		long errors = summary.counter("upstreamErrors");
		if (errors != 0) {
			findings.add(errors(node, report, arrivals, errors));
		}
		double hold = Objects.requireNonNullElse(summary.meanHoldMs(), 0.0);
		double median = Objects.requireNonNullElse(report.summary().latencyMs().p50(), 0.0);
		long arrived = report.summary().arrived();
		boolean slowCall = hold != 0 && median != 0 && hold >= DOMINANT_CALL * median;
		if (slowCall && arrived != 0 && arrivals >= CALLED_BY * arrived) {
			findings.add(new RunFinding(FindingSeverity.INFO, FindingKind.EXTERNAL_LATENCY, node.id(),
					node.label() + " dominates request latency",
					"Calls to " + node.label() + " take " + FindingText.wholeWithSeparators(hold)
							+ " ms on average, while the median request takes "
							+ FindingText.wholeWithSeparators(median) + " ms end to end.",
					CALL_ASYNCHRONOUSLY,
					RunFinding.emptyEvidence()
						.put("meanCallMs", PythonRound.round(hold, TIME_DECIMALS))
						.put("requestP50Ms", PythonRound.round(median, TIME_DECIMALS))));
		}
		return findings;
	}

	private static RunFinding errors(ReportNode node, RunReport report, long arrivals, long errors) {
		double share = (double) errors / arrivals;
		long reached = report.summary()
			.failureBreakdown()
			.stream()
			.filter((cause) -> node.id().equals(cause.nodeId()) && FailureReason.UPSTREAM_ERROR.is(cause.reason()))
			.mapToLong(FailureCause::count)
			.sum();
		String detail = FindingText.count(errors) + " of " + FindingText.count(arrivals) + " calls ("
				+ FindingText.percentToTenths(share) + ") failed with provider errors"
				+ ((reached != 0) ? "; " + FindingText.count(reached, "client request") + " failed because of them."
						: "; none reached the clients.");
		return new RunFinding((share >= FAILS_OFTEN || reached != 0) ? FindingSeverity.WARNING : FindingSeverity.INFO,
				FindingKind.EXTERNAL_ERRORS, node.id(),
				node.label() + " failed " + FindingText.percentToTenths(share) + " of calls", detail, RETRY_PROVIDER,
				RunFinding.emptyEvidence()
					.put("errors", errors)
					.put("arrivals", arrivals)
					.put("share", PythonRound.round(share, DataFindings.SHARE_DECIMALS))
					.put("reachedClients", reached));
	}

	/**
	 * Requests a part turned away because they exceeded its provisioned throughput, rate
	 * limit or concurrency limit: data stores reject them, third parties answer them as
	 * failed calls.
	 * @param node the part
	 * @param run the run, for the part's settings
	 * @return the finding, when more than 1% of enough arrivals were turned away
	 */
	static Optional<RunFinding> throttling(ReportNode node, RunContext run) {
		NodeSummary summary = node.summary();
		long arrivals = Objects.requireNonNullElse(summary.arrivals(), 0L);
		if (arrivals < DataFindings.MIN_SAMPLES) {
			return Optional.empty();
		}
		long throttled = summary.rejectedFor(FailureReason.THROTTLED.json()) + summary.counter("throttled");
		long limited = summary.rejectedFor(FailureReason.RATE_LIMITED.json()) + summary.counter("rateLimited");
		long count = throttled + limited;
		double share = (double) count / arrivals;
		if (share <= THROTTLED) {
			return Optional.empty();
		}
		boolean database = DatabaseSpec.TYPE.equals(node.type());
		return Optional.of(new RunFinding(
				(share > MOSTLY_THROTTLED) ? FindingSeverity.CRITICAL : FindingSeverity.WARNING, FindingKind.THROTTLING,
				node.id(), node.label() + " throttled " + FindingText.percent(share) + " of requests",
				FindingText.count(count) + " of " + FindingText.count(arrivals) + " requests ("
						+ FindingText.percentToTenths(share) + ") were turned away because they exceeded "
						+ (database ? provisioned(node, run) : limits(node, run, throttled, limited)) + ".",
				database ? RAISE_THROUGHPUT : STAY_UNDER_LIMIT,
				RunFinding.emptyEvidence()
					.put("throttled", throttled)
					.put("rateLimited", limited)
					.put("arrivals", arrivals)
					.put("share", PythonRound.round(share, DataFindings.SHARE_DECIMALS))));
	}

	private static String provisioned(ReportNode node, RunContext run) {
		Optional<DatabaseConfig> config = run.config(node.id(), DatabaseConfig.class);
		double limit = config.map(DatabaseConfig::throughputLimitRps).orElse(0.0);
		int shards = config.map(DatabaseConfig::shards).orElse(1);
		return "the provisioned throughput of " + FindingText.wholeWithSeparators(limit) + " ops/s per shard ("
				+ FindingText.count(shards, "shard") + ")";
	}

	private static String limits(ReportNode node, RunContext run, long throttled, long limited) {
		List<String> limits = new ArrayList<>();
		if (limited != 0) {
			limits.add("the rate limit of " + FindingText.wholeWithSeparators(rateLimit(node.id(), run)) + " req/s");
		}
		if (throttled != 0) {
			limits.add("the concurrency limit of " + FindingText.count(concurrencyLimit(node.id(), run))
					+ " calls in flight");
		}
		return limits.isEmpty() ? "the provider's limits" : String.join(" and ", limits);
	}

	private static double rateLimit(String id, RunContext run) {
		Double limit = switch (run.configs().get(id)) {
			case ExternalApiConfig external -> external.rateLimitRps();
			case LoadBalancerConfig balancer -> balancer.rateLimitRps();
			case null, default -> null;
		};
		return Objects.requireNonNullElse(limit, 0.0);
	}

	private static long concurrencyLimit(String id, RunContext run) {
		Integer limit = switch (run.configs().get(id)) {
			case ExternalApiConfig external -> external.concurrencyLimit();
			case ServiceConfig service -> service.concurrencyLimit();
			case null, default -> null;
		};
		return Objects.requireNonNullElse(limit, 0);
	}

	/**
	 * A cluster's pods waiting for a node with room for their requests.
	 * @param node the cluster
	 * @param run the run, for the cluster's settings
	 * @return the finding, when a pod waited
	 */
	static Optional<RunFinding> pendingPods(ReportNode node, RunContext run) {
		NodeSummary summary = node.summary();
		long peak = Objects.requireNonNullElse(summary.podsPendingPeak(), 0L);
		if (peak <= 0) {
			return Optional.empty();
		}
		long adds = summary.counter("nodeAdds");
		double longest = Objects.requireNonNullElse(summary.maxPendingS(), 0.0);
		long end = Objects.requireNonNullElse(summary.podsPendingEnd(), 0L);
		Optional<ClusterConfig> config = run.config(node.id(), ClusterConfig.class)
			.filter(ClusterConfig::clusterAutoscaler);
		String waited = FindingText.wholeWithoutSeparators(longest);
		String detail = "Up to " + peak + " pod(s) waited for a node with room for their requests, the longest "
				+ waited + " s. "
				+ config
					.map((cluster) -> "The cluster autoscaler added " + adds + " node(s), each ready about "
							+ FindingText.wholeWithoutSeparators(cluster.nodeProvisionS()) + " s after it was asked for"
							+ ((end != 0) ? "; " + end + " pod(s) were still Pending at the end." : "."))
					.orElse("The cluster autoscaler is off, so Pending pods wait until other pods are removed.");
		return Optional
			.of(new RunFinding((end != 0 || longest >= LONG_WAIT_S) ? FindingSeverity.WARNING : FindingSeverity.INFO,
					FindingKind.PODS_PENDING, node.id(),
					"Pods waited up to " + waited + " s for room on " + node.label(), detail, KEEP_HEADROOM,
					RunFinding.emptyEvidence()
						.put("podsPendingPeak", peak)
						.put("maxPendingS", longest)
						.put("nodeAdds", adds)
						.put("podsPendingEnd", end)));
	}

}
