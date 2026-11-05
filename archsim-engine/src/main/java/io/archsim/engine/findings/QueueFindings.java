package io.archsim.engine.findings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.RunReport;
import io.archsim.model.component.queue.MessageQueueSpec;

/** How far each queue's consumers fell behind: lag, wait, age, dead letters, backlog. */
final class QueueFindings {

	/** A p95 wait above this, in milliseconds, is worth a warning. */
	private static final double LONG_WAIT_MS = 10_000;

	private static final String CATCH_UP = "Add consumers (and partitions), make consumers faster, or fix the failing "
			+ "downstream; lag is acceptable only while it drains within your freshness target.";

	private QueueFindings() {
	}

	/**
	 * The findings of a run's queues.
	 * @param report the run's report
	 * @return a finding for each queue
	 */
	static List<RunFinding> of(RunReport report) {
		List<RunFinding> findings = new ArrayList<>();
		for (ReportNode node : report.nodes()) {
			if (MessageQueueSpec.TYPE.equals(node.type())) {
				findings.add(queue(node));
			}
		}
		return findings;
	}

	private static RunFinding queue(ReportNode node) {
		NodeSummary summary = node.summary();
		long peak = lagPeak(node);
		long processed = summary.counter("processed");
		long dead = summary.counter("deadLettered");
		long backlog = Objects.requireNonNullElse(summary.backlogAtEnd(), 0L);
		Double wait = summary.p95QueueWaitMs();
		Double age = summary.p95MessageAgeMs();
		List<String> parts = new ArrayList<>(List.of(
				"Messages waited " + FindingText.ms(wait) + " (p95) before a consumer picked them up; "
						+ "publish-to-processed age p95 " + FindingText.ms(age) + ".",
				FindingText.count(processed) + " processed, " + FindingText.count(dead) + " dead-lettered."));
		if (backlog != 0) {
			parts.add(FindingText.count(backlog) + " messages were still queued when the run ended.");
		}
		boolean longWait = wait != null && wait > LONG_WAIT_MS;
		boolean behind = backlog != 0 || dead != 0 || longWait;
		return new RunFinding(behind ? FindingSeverity.WARNING : FindingSeverity.INFO, FindingKind.QUEUE_LAG, node.id(),
				(peak > 0) ? node.label() + " lag peaked at " + FindingText.count(peak) + " messages"
						: node.label() + " kept up with no backlog",
				String.join(" ", parts), behind ? CATCH_UP : null,
				RunFinding.emptyEvidence()
					.put("lagPeak", peak)
					.put("p95WaitMs", wait)
					.put("p95AgeMs", age)
					.put("processed", processed)
					.put("deadLettered", dead)
					.put("backlogAtEnd", backlog));
	}

	// The most messages the queue held at once, as a whole number.
	private static long lagPeak(ReportNode node) {
		return (long) node.series("queueMax")
			.stream()
			.filter(Objects::nonNull)
			.mapToDouble(Double::doubleValue)
			.max()
			.orElse(0.0);
	}

}
