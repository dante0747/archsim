package io.archsim.engine.findings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.RunReport;
import io.archsim.engine.text.QueueText;
import io.archsim.model.component.queue.MessageQueueSpec;

/**
 * How far each queue's consumers fell behind: lag, wait, age, dead letters, backlog, and
 * the consumers its partitions left idle.
 */
final class QueueFindings {

	/** A p95 wait above this, in milliseconds, is worth a warning. */
	private static final double LONG_WAIT_MS = 10_000;

	/** How a queue behind catches up, whatever adds the consumers it needs. */
	private static final String CATCH_UP_REST = "make consumers faster, or fix the failing downstream; lag is "
			+ "acceptable only while it drains within your freshness target.";

	private static final String CATCH_UP = "Add consumers (and partitions), " + CATCH_UP_REST;

	/** A queue behind with idle consumers needs partitions to put them to work. */
	private static final String IDLE_CATCH_UP = "Add partitions so the idle consumers get work, " + CATCH_UP_REST;

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
		Lag lag = Lag.of(node);
		List<String> parts = new ArrayList<>(List.of(
				"Messages waited " + FindingText.ms(lag.waitMs()) + " (p95) before a consumer picked them up; "
						+ "publish-to-processed age p95 " + FindingText.ms(lag.ageMs()) + ".",
				FindingText.count(lag.processed()) + " processed, " + FindingText.count(lag.dead())
						+ " dead-lettered."));
		if (lag.backlog() != 0) {
			parts.add(FindingText.count(lag.backlog()) + " messages were still queued when the run ended.");
		}
		if (lag.idle() > 0) {
			// a partition caps a group's consumers, which the review advises on too
			// (ADR-0085)
			parts.add(lag.idle() + " of the " + (lag.working() + lag.idle()) + " consumers sat idle: only "
					+ QueueText.ONE_CONSUMER_PER_PARTITION + ", so " + lag.working() + " partitions keep at most "
					+ lag.working() + " of them busy.");
		}
		boolean longWait = lag.waitMs() != null && lag.waitMs() > LONG_WAIT_MS;
		boolean behind = lag.backlog() != 0 || lag.dead() != 0 || longWait;
		return new RunFinding(behind ? FindingSeverity.WARNING : FindingSeverity.INFO, FindingKind.QUEUE_LAG, node.id(),
				(lag.peak() > 0) ? node.label() + " lag peaked at " + FindingText.count(lag.peak()) + " messages"
						: node.label() + " kept up with no backlog",
				String.join(" ", parts), advice(lag, behind), lag.evidence());
	}

	// a queue behind wants catching up, through partitions when consumers sit idle; one
	// that kept up wants its idle consumers put to work or let go
	private static @Nullable String advice(Lag lag, boolean behind) {
		if (behind) {
			return (lag.idle() > 0) ? IDLE_CATCH_UP : CATCH_UP;
		}
		return (lag.idle() > 0) ? QueueText.IDLE_CONSUMERS_ADVICE : null;
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

	/**
	 * What a queue's run shows of its lag.
	 *
	 * @param peak the most messages it held at once
	 * @param processed the messages processed
	 * @param dead the messages dead-lettered
	 * @param backlog the messages still queued at the end
	 * @param waitMs the p95 wait before a consumer took a message
	 * @param ageMs the p95 age of a message when processed
	 * @param idle the consumers beyond those its partitions let work
	 * @param working the consumers its partitions let work, one a partition
	 */
	private record Lag(long peak, long processed, long dead, long backlog, @Nullable Double waitMs,
			@Nullable Double ageMs, long idle, long working) {

		static Lag of(ReportNode node) {
			NodeSummary summary = node.summary();
			return new Lag(lagPeak(node), summary.counter("processed"), summary.counter("deadLettered"),
					Objects.requireNonNullElse(summary.backlogAtEnd(), 0L), summary.p95QueueWaitMs(),
					summary.p95MessageAgeMs(), Objects.requireNonNullElse(summary.idleConsumers(), 0L),
					Objects.requireNonNullElse(summary.effectiveConsumers(), 0L));
		}

		ObjectNode evidence() {
			ObjectNode evidence = RunFinding.emptyEvidence()
				.put("lagPeak", this.peak)
				.put("p95WaitMs", this.waitMs)
				.put("p95AgeMs", this.ageMs)
				.put("processed", this.processed)
				.put("deadLettered", this.dead)
				.put("backlogAtEnd", this.backlog);
			if (this.idle > 0) {
				evidence.put("idleConsumers", this.idle);
			}
			return evidence;
		}

	}

}
