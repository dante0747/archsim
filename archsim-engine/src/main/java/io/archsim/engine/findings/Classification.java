package io.archsim.engine.findings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.report.ReportNode;
import io.archsim.model.component.Category;
import io.archsim.model.component.kubernetes.KubernetesClusterSpec;
import io.archsim.model.component.queue.MessageQueueSpec;

/**
 * How loaded a part was in a run, and why.
 *
 * @param status how loaded
 * @param reasons why, in words
 */
public record Classification(PartStatus status, List<String> reasons) {

	/** At this utilization (or CPU) a part is saturated. */
	static final double SATURATED = 0.95;

	/** At this utilization (or CPU) a part is hot. */
	static final double HOT = 0.85;

	/** Why a message queue is hot: its backlog kept growing. */
	static final String BACKLOG_GREW = "backlog kept growing";

	/** Why a part is saturated: its queue kept growing. */
	static final String QUEUE_GREW = "queue kept growing";

	/** At this utilization a part is warm. */
	private static final double WARM = 0.70;

	/** At this share of its arrivals rejected a part is saturated. */
	private static final double REJECTING = 0.01;

	private static final Classification OK = new Classification(PartStatus.OK, List.of());

	/**
	 * Copies the reasons.
	 */
	public Classification {
		reasons = List.copyOf(reasons);
	}

	/**
	 * Classifies a part of a run.
	 * @param node the part in the run's report
	 * @param from the first bucket of the measured window
	 * @param to the bucket after its last
	 * @return its status and why; the calls an injected outage refused are named, and do
	 * not count as overload
	 */
	static Classification of(ReportNode node, int from, int to) {
		Classification load = ofLoad(node, from, to);
		long refused = PartLoad.outageRefusals(node.summary());
		if (refused == 0) {
			return load;
		}
		List<String> reasons = new ArrayList<>(load.reasons());
		reasons.add("refused " + FindingText.count(refused) + " calls while down (planned outage)");
		return new Classification(load.status(), reasons);
	}

	private static Classification ofLoad(ReportNode node, int from, int to) {
		NodeSummary summary = node.summary();
		if (Category.TRAFFIC.json().equals(node.category())) {
			return OK;
		}
		if (KubernetesClusterSpec.TYPE.equals(node.type())) {
			return ofCluster(summary);
		}
		if (Objects.requireNonNullElse(summary.arrivals(), 0L) == 0) {
			return new Classification(PartStatus.IDLE, List.of("received no requests"));
		}
		double utilization = Objects.requireNonNullElse(summary.utilization(), 0.0);
		double rejection = PartLoad.loadRejectionRate(summary);
		boolean growing = PartLoad.queueGrowing(node.series("queueMean"), from, to);
		if (MessageQueueSpec.TYPE.equals(node.type()) && rejection < REJECTING && (utilization >= HOT || growing)) {
			// producers still get their acks: a saturated consumer group shows as lag
			List<String> reasons = new ArrayList<>(List.of("consumers " + FindingText.percent(utilization) + " busy"));
			if (growing) {
				reasons.add(BACKLOG_GREW);
			}
			return new Classification(PartStatus.HOT, reasons);
		}
		return ofServer(utilization, summary.cpuCoreUtilization(), rejection, growing);
	}

	// A cluster is judged by its pods: it is not on the request path.
	private static Classification ofCluster(NodeSummary summary) {
		Long pendingEnd = summary.podsPendingEnd();
		if (pendingEnd != null && pendingEnd != 0) {
			return new Classification(PartStatus.SATURATED, List.of(pendingEnd + " pod(s) still Pending at the end"));
		}
		Long pendingPeak = summary.podsPendingPeak();
		if (pendingPeak != null && pendingPeak != 0) {
			return new Classification(PartStatus.HOT, List.of("up to " + pendingPeak + " pod(s) waited for a node"));
		}
		double requested = Objects.requireNonNullElse(summary.cpuRequestedRatio(), 0.0);
		return (requested >= HOT) ? new Classification(PartStatus.WARM,
				List.of(FindingText.percent(requested) + " of node CPU requested")) : OK;
	}

	// A part that serves requests: handlers waiting for a core keep their worker busy,
	// so a CPU-bound part also shows a high utilization, and the reason says why.
	private static Classification ofServer(double utilization, @Nullable Double cpu, double rejection,
			boolean growing) {
		List<String> cpuReason = (cpu != null && cpu >= HOT) ? List.of("CPU " + FindingText.percent(cpu) + " busy")
				: List.of();
		List<String> reasons = new ArrayList<>();
		if (utilization >= SATURATED) {
			reasons.add("utilization " + FindingText.percent(utilization));
		}
		if (cpu != null && cpu >= SATURATED) {
			reasons.addAll(cpuReason);
		}
		if (rejection >= REJECTING) {
			reasons.add(FindingText.percentToTenths(rejection) + " of requests rejected");
		}
		if (growing) {
			reasons.add(QUEUE_GREW);
		}
		if (!reasons.isEmpty()) {
			return new Classification(PartStatus.SATURATED, reasons);
		}
		return ofUtilization(utilization, cpu, cpuReason);
	}

	private static Classification ofUtilization(double utilization, @Nullable Double cpu, List<String> cpuReason) {
		if (utilization >= HOT) {
			List<String> reasons = new ArrayList<>(List.of("utilization " + FindingText.percent(utilization)));
			reasons.addAll(cpuReason);
			return new Classification(PartStatus.HOT, reasons);
		}
		if (cpu != null && cpu >= HOT) {
			return new Classification(PartStatus.HOT, cpuReason);
		}
		if (utilization >= WARM) {
			return new Classification(PartStatus.WARM, List.of("utilization " + FindingText.percent(utilization)));
		}
		return OK;
	}

}
