package io.archsim.engine.findings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoublePredicate;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.report.ReportNode;
import io.archsim.model.component.service.Platform;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.component.service.ServiceSpec;

/** Findings about how services scaled: serverless cold starts, and autoscaling. */
final class ServiceFindings {

	/**
	 * Above this share of its requests cold, a serverless function's cold starts matter.
	 */
	private static final double COLD_SHARE = 0.05;

	/** Replica counts closer than this are the same. */
	private static final double SAME = 0.01;

	/** At this utilization at its maximum, a service has no room left to scale. */
	private static final double NO_ROOM = 0.85;

	private static final String SCALE_SUGGESTION = "Scale earlier (lower target utilization or a shorter evaluation "
			+ "interval), keep more minimum replicas, or reduce start-up time.";

	private static final String KEEP_WARM = "Keep instances warm (provisioned concurrency) or cut "
			+ "cold-start time (smaller bundle, lighter runtime).";

	private ServiceFindings() {
	}

	/**
	 * The findings of a run's services.
	 * @param run the run
	 * @param series the run's client-side time series
	 * @return a serverless function's cold starts; an autoscaled service's scaling, and
	 * reaching its maximum
	 */
	static List<RunFinding> of(RunContext run, ClientSeries series) {
		List<RunFinding> findings = new ArrayList<>();
		for (ReportNode node : run.report().nodes()) {
			if (!ServiceSpec.TYPE.equals(node.type())) {
				continue;
			}
			Optional<ServiceConfig> config = run.config(node.id(), ServiceConfig.class);
			if (config.isEmpty()) {
				continue;
			}
			if (config.get().platform() == Platform.SERVERLESS) {
				findings.add(serverless(node, config.get()));
			}
			else if (config.get().autoscaling()) {
				findings.addAll(autoscaling(node, config.get(), series));
			}
		}
		return findings;
	}

	private static RunFinding serverless(ReportNode node, ServiceConfig config) {
		NodeSummary summary = node.summary();
		long cold = summary.counter("coldStarts");
		long coldTotal = Objects.requireNonNullElse(summary.coldStartsTotal(), cold);
		long throttled = summary.rejectedFor(FailureReason.THROTTLED.json());
		long arrivals = Objects.requireNonNullElse(summary.arrivals(), 0L);
		double share = (arrivals != 0) ? (double) cold / arrivals : 0.0;
		String peak = FindingText.countOrNone(summary.replicasPeak());
		String detail = FindingText.count(coldTotal) + " cold starts in the whole run (" + FindingText.count(cold)
				+ " in the measurement window, " + FindingText.percentToTenths(share) + " of its requests); up to "
				+ peak + " instances ran (limit " + config.concurrencyLimit() + ").";
		if (throttled != 0) {
			detail += " " + FindingText.count(throttled) + " requests were throttled at the concurrency limit.";
		}
		boolean costly = share > COLD_SHARE;
		return new RunFinding(costly ? FindingSeverity.WARNING : FindingSeverity.INFO, FindingKind.SERVERLESS,
				node.id(),
				node.label() + ": " + FindingText.count(coldTotal) + " cold starts, up to " + peak + " instances",
				detail, costly ? KEEP_WARM : null,
				RunFinding.emptyEvidence()
					.put("coldStarts", cold)
					.put("coldStartsTotal", coldTotal)
					.put("throttled", throttled)
					.put("instancesPeak", summary.replicasPeak())
					.put("coldStartShare", share));
	}

	private static List<RunFinding> autoscaling(ReportNode node, ServiceConfig config, ClientSeries series) {
		List<@Nullable Double> replicas = node.series("replicas");
		List<Double> counts = replicas.stream().filter(Objects::nonNull).toList();
		if (counts.isEmpty()) {
			return List.of();
		}
		List<RunFinding> findings = new ArrayList<>();
		double start = counts.getFirst();
		double peak = Collections.max(counts);
		if (peak > start + SAME) {
			findings.add(scaled(node, new Scaling(start, peak, counts.getLast()), series));
		}
		double utilization = PartLoad.utilization(node.summary());
		if (peak >= config.maxReplicas() - SAME && utilization >= NO_ROOM) {
			findings.add(new RunFinding(FindingSeverity.WARNING, FindingKind.AUTOSCALING, node.id(),
					node.label() + " hit its autoscaling maximum (" + config.maxReplicas() + " replicas)",
					"It ran at " + FindingText.percent(utilization) + " utilization with no room left to scale.",
					"Raise max replicas (and check the database and other dependencies can take the extra load).",
					RunFinding.emptyEvidence()
						.put("maxReplicas", config.maxReplicas())
						.put("utilization", utilization)));
		}
		return findings;
	}

	// How an autoscaled service grew to its peak, and what it rejected meanwhile.
	private static RunFinding scaled(ReportNode node, Scaling scaling, ClientSeries series) {
		List<@Nullable Double> replicas = node.series("replicas");
		int firstUp = firstIndex(replicas, (count) -> count > scaling.start() + SAME).orElse(0);
		int peakIndex = firstIndex(replicas, (count) -> count >= scaling.peak() - SAME).orElse(firstUp);
		long rejected = rejectedUntil(node, peakIndex, series.bucket());
		double upAt = series.start(firstUp);
		double peakAt = series.start(peakIndex);
		String detail = "The first new replica was taking traffic by about " + FindingText.general(upAt)
				+ " s and capacity reached " + FindingText.wholeWithoutSeparators(scaling.peak())
				+ " replicas by about " + FindingText.general(peakAt) + " s.";
		if (rejected != 0) {
			detail += " " + FindingText.count(rejected) + " requests were rejected while it scaled.";
		}
		if (scaling.end() < scaling.peak() - SAME) {
			detail += " It scaled back to " + FindingText.wholeWithoutSeparators(scaling.end())
					+ " replicas when load dropped.";
		}
		return new RunFinding((rejected != 0) ? FindingSeverity.WARNING : FindingSeverity.INFO, FindingKind.AUTOSCALING,
				node.id(),
				node.label() + " autoscaled from " + FindingText.wholeWithoutSeparators(scaling.start()) + " to "
						+ FindingText.wholeWithoutSeparators(scaling.peak()) + " replicas",
				detail, (rejected != 0) ? SCALE_SUGGESTION : null,
				RunFinding.emptyEvidence()
					.put("replicasStart", scaling.start())
					.put("replicasPeak", scaling.peak())
					.put("replicasEnd", scaling.end())
					.put("firstScaleUpS", upAt)
					.put("peakReachedS", peakAt)
					.put("rejectedWhileScaling", rejected));
	}

	// The requests a service rejected until its peak bucket, rounded half to even.
	private static long rejectedUntil(ReportNode node, int peakIndex, double bucket) {
		List<@Nullable Double> rejectedRps = node.series("rejectedRps");
		double rejectedSum = FloatSum.of(rejectedRps.subList(0, Math.min(peakIndex + 1, rejectedRps.size()))
			.stream()
			.mapToDouble((rps) -> (rps != null) ? rps : 0.0));
		return (long) Math.rint(rejectedSum * bucket);
	}

	// The first bucket whose count passes a test, the gaps skipped.
	private static Optional<Integer> firstIndex(List<@Nullable Double> series, DoublePredicate test) {
		for (int i = 0; i < series.size(); i++) {
			Double value = series.get(i);
			if (value != null && test.test(value)) {
				return Optional.of(i);
			}
		}
		return Optional.empty();
	}

	/**
	 * An autoscaled service's replicas over the run.
	 *
	 * @param start at the start
	 * @param peak at their most
	 * @param end at the end
	 */
	private record Scaling(double start, double peak, double end) {

	}

}
