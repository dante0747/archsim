package io.archsim.engine.findings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.report.ReportNode;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.kubernetes.KubernetesClusterSpec;
import io.archsim.model.component.loadbalancer.LoadBalancerSpec;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.EdgeSpec;

/**
 * A run's saturated and hot parts, each a root cause or a symptom: a part whose busy time
 * is mostly spent blocked on a saturated part downstream (or a balancer in front of one)
 * is a symptom of it.
 */
final class Bottlenecks {

	/**
	 * Under this share of busy time spent on its own work, a part is held up downstream.
	 */
	private static final double BLOCKED = 0.5;

	private static final Map<String, String> SLOT_NOUN = Map.of(ServiceSpec.TYPE, "workers", DatabaseSpec.TYPE,
			"connections", CacheSpec.TYPE, "command executors", LoadBalancerSpec.TYPE, "connections");

	private final RunContext run;

	private final List<ReportNode> roots;

	private final List<Symptom> symptoms;

	private Bottlenecks(RunContext run, List<ReportNode> roots, List<Symptom> symptoms) {
		this.run = run;
		this.roots = roots;
		this.symptoms = symptoms;
	}

	/**
	 * The bottlenecks of a run.
	 * @param run the run
	 * @return its root causes, the most loaded first, and its symptoms
	 */
	static Bottlenecks of(RunContext run) {
		Map<String, List<String>> calls = new LinkedHashMap<>();
		for (EdgeSpec edge : run.resolved().architecture().edges()) {
			calls.computeIfAbsent(edge.source(), (source) -> new ArrayList<>()).add(edge.target());
		}
		List<ReportNode> roots = new ArrayList<>();
		List<Symptom> symptoms = new ArrayList<>();
		for (ReportNode node : run.report().nodes()) {
			if (!run.status(node).status().isLoaded() || KubernetesClusterSpec.TYPE.equals(node.type())) {
				continue;
			}
			double own = ownShare(node.summary());
			Optional<ReportNode> loadedDownstream = downstream(node.id(), calls).stream()
				.map((id) -> run.report().node(id))
				.flatMap(Optional::stream)
				.filter((down) -> run.status(down).status().isLoaded())
				.findFirst();
			if (loadedDownstream.isPresent() && (own < BLOCKED || LoadBalancerSpec.TYPE.equals(node.type()))) {
				symptoms.add(new Symptom(node, loadedDownstream.get(), own));
			}
			else {
				roots.add(node);
			}
		}
		roots.sort(Comparator.<ReportNode>comparingInt((node) -> run.status(node).status().rank())
			.thenComparingDouble((node) -> PartLoad.loadRejectionRate(node.summary()))
			.thenComparingDouble((node) -> PartLoad.utilization(node.summary()))
			.reversed());
		return new Bottlenecks(run, roots, symptoms);
	}

	/**
	 * Whether the run has no root cause and no symptom.
	 * @return whether no part is saturated or hot
	 */
	boolean none() {
		return this.roots.isEmpty() && this.symptoms.isEmpty();
	}

	/**
	 * The findings: each root cause, then each symptom.
	 * @return the findings
	 */
	List<RunFinding> findings() {
		List<RunFinding> findings = new ArrayList<>();
		for (int rank = 0; rank < this.roots.size(); rank++) {
			ReportNode root = this.roots.get(rank);
			findings.add(RootCause.finding(root, this.run.status(root), rank));
		}
		this.symptoms.forEach((symptom) -> findings.add(symptom.finding(this.run)));
		return findings;
	}

	/**
	 * What a part's slots are called: workers, connections or command executors.
	 * @param type the part's type
	 * @return their name
	 */
	static String slotNoun(String type) {
		return SLOT_NOUN.getOrDefault(type, "slots");
	}

	// The share of a part's busy slot time that is its own processing.
	private static double ownShare(NodeSummary summary) {
		double utilization = PartLoad.utilization(summary);
		Double cpu = summary.cpuUtilization();
		return (cpu != null && utilization != 0) ? cpu / utilization : 1.0;
	}

	// The parts a part calls, directly or not, nearest first.
	private static List<String> downstream(String start, Map<String, List<String>> calls) {
		Set<String> seen = new HashSet<>(Set.of(start));
		List<String> order = new ArrayList<>();
		Deque<String> queue = new ArrayDeque<>(calls.getOrDefault(start, List.of()));
		while (!queue.isEmpty()) {
			String next = queue.removeFirst();
			if (seen.add(next)) {
				order.add(next);
				queue.addAll(calls.getOrDefault(next, List.of()));
			}
		}
		return order;
	}

	/**
	 * A saturated or hot part held up by a part downstream.
	 *
	 * @param node the part
	 * @param down the loaded part downstream that holds it up
	 * @param own the share of its busy time that is its own processing
	 */
	private record Symptom(ReportNode node, ReportNode down, double own) {

		RunFinding finding(RunContext run) {
			NodeSummary summary = this.node.summary();
			String downStatus = run.status(this.down).status().json();
			String detail;
			if (LoadBalancerSpec.TYPE.equals(this.node.type())) {
				detail = "It is backed up because " + this.down.label() + " downstream is " + downStatus + ".";
			}
			else {
				detail = "Its " + slotNoun(this.node.type()) + " were "
						+ FindingText.percent(PartLoad.utilization(summary)) + " occupied, but only "
						+ FindingText.percent(this.own) + " of that time was its own "
						+ "processing; the rest was spent blocked on downstream calls while " + this.down.label()
						+ " is " + downStatus + ".";
			}
			return new RunFinding(FindingSeverity.WARNING, FindingKind.SYMPTOM, this.node.id(),
					this.node.label() + " is saturated as a consequence of " + this.down.label(),
					detail + " Relieving " + this.down.label() + " should relieve it too.", null,
					RunFinding.emptyEvidence()
						.put("utilization", summary.utilization())
						.put("cpuUtilization", summary.cpuUtilization())
						.put("blockedOn", this.down.id()));
		}

	}

}
