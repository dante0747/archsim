package io.archsim.engine.findings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.report.FailureReason;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.report.ReportNode;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.loadbalancer.LoadBalancerSpec;
import io.archsim.model.component.queue.MessageQueueSpec;
import io.archsim.model.component.service.ServiceSpec;

/**
 * The finding of a part that is a root cause: how busy its slots were, its queue, what it
 * rejected, what limits its capacity, and how much of its busy time it spent blocked.
 */
final class RootCause {

	/** A peak at least this far above the mean is named. */
	private static final double NOTABLE_PEAK = 0.1;

	/** A queue averaging at least this many requests is named. */
	private static final double NOTABLE_QUEUE = 0.5;

	/** Under this share of busy time spent on its own work, a part waits on others. */
	private static final double WAITS_ON_OTHERS = 0.7;

	private static final Map<String, String> SUGGESTION = Map.of(ServiceSpec.TYPE,
			"Add replicas or workers per replica, or reduce the service time.", DatabaseSpec.TYPE,
			"Raise the connection limit only if the database has spare CPU/IO; otherwise raise the cache hit ratio, "
					+ "add read replicas or optimize the slow queries.",
			CacheSpec.TYPE, "Add command executors (shard the cache) or reduce command time.", LoadBalancerSpec.TYPE,
			"Raise the balancer's connection limit or scale it out.");

	private RootCause() {
	}

	/**
	 * The finding of a root cause.
	 * @param node the part
	 * @param status its status and why
	 * @param rank its place among the run's root causes, the most loaded first
	 * @return the finding: a bottleneck when it is saturated, else a hot part
	 */
	static RunFinding finding(ReportNode node, Classification status, int rank) {
		NodeSummary summary = node.summary();
		double utilization = PartLoad.utilization(summary);
		List<String> parts = new ArrayList<>();
		parts.add(slotsBusy(node, utilization));
		queue(summary, status).ifPresent(parts::add);
		parts.addAll(rejections(summary));
		capacity(summary).ifPresent(parts::add);
		String suggestion = suggestion(node.type(), summary.capacityLimit());
		Double cpu = summary.cpuUtilization();
		if (cpu != null && utilization != 0 && cpu / utilization < WAITS_ON_OTHERS) {
			parts.add("Only " + FindingText.percent(cpu / utilization) + " of busy worker time was its own processing; "
					+ "the rest was spent blocked on downstream calls.");
			suggestion += " Faster dependencies (or non-blocking I/O) would also free workers.";
		}
		boolean saturated = status.status() == PartStatus.SATURATED;
		return new RunFinding(saturated ? FindingSeverity.CRITICAL : FindingSeverity.WARNING,
				saturated ? FindingKind.BOTTLENECK : FindingKind.HOT, node.id(), title(node, status, rank),
				String.join(" ", parts), suggestion,
				RunFinding.emptyEvidence()
					.put("utilization", summary.utilization())
					.put("meanQueue", summary.meanQueue())
					.put("peakQueue", summary.peakQueue())
					.put("rejectedTotal", summary.rejectedTotal())
					.put("arrivalRps", summary.arrivalRps())
					.put("capacityRps", summary.capacityRps()));
	}

	// How busy its slots were, or, for a part without slots, what it received.
	private static String slotsBusy(ReportNode node, double utilization) {
		NodeSummary summary = node.summary();
		Double slots = summary.slots();
		if (slots == null || summary.utilization() == null) {
			return "It received " + FindingText.rps(summary.arrivalRps()) + ".";
		}
		String noun = Bottlenecks.slotNoun(node.type());
		// an autoscaled part's slots are a time average: rounded, and said so
		String count = (slots == Math.rint(slots)) ? FindingText.wholeWithSeparators(slots) + " " + noun
				: noun + " (about " + FindingText.wholeWithSeparators(slots) + " on average as it scaled)";
		String sentence = "On average " + FindingText.percent(utilization) + " of its " + count + " were busy";
		Double peak = summary.peakUtilization();
		if (peak != null && peak - utilization >= NOTABLE_PEAK) {
			sentence += ", peaking at " + FindingText.percent(peak);
		}
		return sentence + ".";
	}

	private static Optional<String> queue(NodeSummary summary, Classification status) {
		double meanQueue = Objects.requireNonNullElse(summary.meanQueue(), 0.0);
		Long peakQueue = summary.peakQueue();
		if (meanQueue < NOTABLE_QUEUE && (peakQueue == null || peakQueue == 0)) {
			return Optional.empty();
		}
		String queue = "The queue averaged " + FindingText.tenths(meanQueue) + " requests (peak "
				+ FindingText.count(Objects.requireNonNull(peakQueue, "a queue's peak")) + ")";
		if (status.reasons().contains(Classification.QUEUE_GREW)) {
			queue += " and kept growing";
		}
		return Optional.of(queue + ".");
	}

	// What its load rejected, by reason, and what it refused while an outage had it down.
	private static List<String> rejections(NodeSummary summary) {
		List<String> sentences = new ArrayList<>();
		Map<String, Long> byLoad = new LinkedHashMap<>();
		summary.rejected().forEach((reason, count) -> {
			if (!FailureReason.OUTAGE.is(reason) && count != 0) {
				byLoad.put(reason, count);
			}
		});
		if (!byLoad.isEmpty()) {
			String reasons = byLoad.entrySet()
				.stream()
				.map((rejected) -> rejected.getKey().replace('_', ' ') + ": " + FindingText.count(rejected.getValue()))
				.collect(Collectors.joining(", "));
			long total = byLoad.values().stream().mapToLong(Long::longValue).sum();
			sentences.add(FindingText.count(total) + " requests ("
					+ FindingText.percentToTenths(PartLoad.loadRejectionRate(summary)) + ") were rejected (" + reasons
					+ ").");
		}
		long refused = PartLoad.outageRefusals(summary);
		if (refused != 0) {
			sentences.add("Separately, it refused " + FindingText.count(refused)
					+ " calls while a planned outage had it down.");
		}
		return sentences;
	}

	private static Optional<String> capacity(NodeSummary summary) {
		Double capacity = summary.capacityRps();
		if (capacity == null || capacity == 0) {
			return Optional.empty();
		}
		String sustains = FindingText.rps(capacity);
		String received = FindingText.rps(summary.arrivalRps());
		return Optional.of(switch (Objects.requireNonNullElse(summary.capacityLimit(), "")) {
			case "cpu" -> "Its CPU cores can do about " + sustains + " at " + tenths(summary.meanServiceMs())
					+ " ms of work per request; it received " + received + ".";
			case "memory" -> "Its memory holds too few requests: about " + sustains + " at the observed "
					+ tenths(summary.meanHoldMs()) + " ms per request; it received " + received + ".";
			default -> "At the observed " + tenths(summary.meanHoldMs()) + " ms per request it can sustain about "
					+ sustains + "; it received " + received + ".";
		});
	}

	private static String suggestion(String type, @Nullable String limit) {
		if ("cpu".equals(limit)) {
			return "Give it more CPU cores (or more replicas/instances), or reduce the CPU work per request.";
		}
		if ("memory".equals(limit)) {
			return "Give each replica more memory, or lower the memory each request needs.";
		}
		return SUGGESTION.getOrDefault(type, "Increase this component's capacity.");
	}

	private static String title(ReportNode node, Classification status, int rank) {
		if (status.status() == PartStatus.SATURATED) {
			return node.label() + ((rank == 0) ? " is the primary bottleneck" : " is also saturated");
		}
		double utilization = PartLoad.utilization(node.summary());
		if (MessageQueueSpec.TYPE.equals(node.type()) && status.reasons().contains(Classification.BACKLOG_GREW)) {
			return node.label() + "'s backlog grew (consumers " + FindingText.percent(utilization) + " busy)";
		}
		// hot from its CPU cores rather than its slots: it says so
		Double cores = node.summary().cpuCoreUtilization();
		String busy = (utilization < Classification.HOT && cores != null && cores >= Classification.HOT)
				? "CPU " + FindingText.percent(cores) + " busy" : FindingText.percent(utilization) + " busy";
		return node.label() + " is running hot (" + busy + ")";
	}

	private static String tenths(@Nullable Double value) {
		return FindingText.tenths(Objects.requireNonNull(value, "a part with a capacity has its times"));
	}

}
