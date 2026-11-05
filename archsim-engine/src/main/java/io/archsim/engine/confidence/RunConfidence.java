package io.archsim.engine.confidence;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.findings.FindingKind;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.provenance.Input;
import io.archsim.engine.report.LatencyShare;
import io.archsim.engine.report.ReportEdge;
import io.archsim.engine.report.ReportFinding;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.RunReport;
import io.archsim.engine.technology.TechnologyNames;
import io.archsim.engine.technology.TechnologyTiers;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.Architecture;

/**
 * A run's confidence ({@code report_confidence}): each part and link weighed by its share
 * of a traced request's time, or each part by its utilization when that breakdown weighs
 * none of them; the bottleneck the run found, else its busiest part.
 */
public final class RunConfidence {

	/** What the confidence's reasons call the part that limits a run. */
	private static final String BOTTLENECK_WORD = "bottleneck";

	/**
	 * Orders parts as Python compares {@code (utilization, id)}, {@code -0.0} equal to
	 * {@code 0.0}: the greatest is the busiest, a tie going to the greater id.
	 */
	private static final Comparator<PartUtilization> BY_UTILIZATION_THEN_ID = RunConfidence::compare;

	private final TechnologyTiers tiers;

	private final TechnologyNames names;

	/**
	 * Assesses runs of designs made of a registry's types.
	 * @param registry the component types
	 */
	public RunConfidence(ComponentRegistry registry) {
		this.tiers = new TechnologyTiers(registry);
		this.names = new TechnologyNames(registry);
	}

	/**
	 * The confidence a run deserves.
	 * @param report the run's report
	 * @param simulated the architecture the run simulated: its parts' technologies, and
	 * its links' names
	 * @param inputs each input of the design, with where its value came from
	 * @param linkMs each link's mean one-way latency, in milliseconds, by its id
	 * @return the confidence
	 */
	public Confidence of(RunReport report, Architecture simulated, List<Input> inputs, Map<String, Double> linkMs) {
		Weights weights = weights(report, linkMs);
		return new Evidence(inputs, weights.values(), weights.weightedBy(),
				this.names.named(this.tiers.of(simulated.nodes())), bottleneck(report), onPath(report),
				AnalysisConfidence.linkLabels(simulated), BOTTLENECK_WORD)
			.assess();
	}

	/**
	 * Each part's and link's share of a traced request's time ({@code report_weights}),
	 * the network's share split among the links by their calls times their latency; or,
	 * when the breakdown weighs no part or link, each busy part's utilization, at most
	 * one.
	 * @param report the run's report
	 * @param linkMs each link's mean one-way latency, by its id
	 * @return the weights, in the order weighed, and what they are
	 */
	private static Weights weights(RunReport report, Map<String, Double> linkMs) {
		Map<PartKey, Double> weights = new LinkedHashMap<>();
		for (LatencyShare row : report.latencyBreakdown()) {
			String nodeId = row.nodeId();
			if (nodeId != null && !nodeId.isEmpty()) {
				weights.put(PartKey.node(nodeId), Objects.requireNonNullElse(row.share(), 0.0));
			}
			else if (LatencyShare.NETWORK.equals(row.label())) {
				weighLinks(weights, Objects.requireNonNullElse(row.share(), 0.0), report.edges(), linkMs);
			}
		}
		if (!weights.isEmpty()) {
			return new Weights(weights, WeightedBy.LATENCY);
		}
		for (ReportNode node : report.nodes()) {
			Double busy = node.summary().utilization();
			if (busy != null && busy > 0) {
				weights.put(PartKey.node(node.id()), Math.min(1.0, busy));
			}
		}
		return new Weights(weights, WeightedBy.UTILIZATION);
	}

	/**
	 * Weighs the links by the network's share of the time: each by its calls times its
	 * latency, out of all the links'. A link without calls or latency weighs nothing.
	 * @param weights the weights so far
	 * @param network the network's share of the time
	 * @param edges the run's links
	 * @param linkMs each link's mean one-way latency, by its id
	 */
	private static void weighLinks(Map<PartKey, Double> weights, double network, List<ReportEdge> edges,
			Map<String, Double> linkMs) {
		Map<String, Double> use = new LinkedHashMap<>();
		for (ReportEdge edge : edges) {
			use.put(edge.id(), Objects.requireNonNullElse(edge.callRps(), 0.0) * linkMs.getOrDefault(edge.id(), 0.0));
		}
		double total = FloatSum.of(use.values().stream().mapToDouble(Double::doubleValue));
		use.forEach((id, used) -> {
			if (total > 0 && used > 0) {
				weights.put(PartKey.edge(id), network * used / total);
			}
		});
	}

	/**
	 * The part that limits the run: the one its first bottleneck finding names, else the
	 * busiest, which would saturate first (a healthy run names no bottleneck).
	 * @param report the run's report
	 * @return the part's id; {@code null} when no part has a utilization
	 */
	private static @Nullable String bottleneck(RunReport report) {
		String named = report.firstFinding(FindingKind.BOTTLENECK.json()).map(ReportFinding::nodeId).orElse(null);
		if (named != null) {
			return named;
		}
		return report.nodes()
			.stream()
			.flatMap((node) -> Optional.ofNullable(node.summary().utilization())
				.map((utilization) -> new PartUtilization(utilization, node.id()))
				.stream())
			.max(BY_UTILIZATION_THEN_ID)
			.map(PartUtilization::id)
			.orElse(null);
	}

	/**
	 * The parts on the request path: those in the breakdown of a request's time, else
	 * those that had arrivals.
	 * @param report the run's report
	 * @return their ids; none when no part qualifies
	 */
	private static Set<String> onPath(RunReport report) {
		Set<String> onPath = new LinkedHashSet<>();
		for (LatencyShare row : report.latencyBreakdown()) {
			String nodeId = row.nodeId();
			if (nodeId != null && !nodeId.isEmpty()) {
				onPath.add(nodeId);
			}
		}
		if (onPath.isEmpty()) {
			for (ReportNode node : report.nodes()) {
				Long arrivals = node.summary().arrivals();
				if (arrivals != null && arrivals != 0) {
					onPath.add(node.id());
				}
			}
		}
		return onPath;
	}

	/**
	 * The order of two parts as Python compares {@code (utilization, id)}: by
	 * utilization, equal ones ({@code -0.0} and {@code 0.0} among them) by their ids.
	 * @param first a part
	 * @param second another part
	 * @return the order of the two
	 */
	private static int compare(PartUtilization first, PartUtilization second) {
		if (first.utilization() != second.utilization()) {
			return (first.utilization() < second.utilization()) ? -1 : 1;
		}
		return first.id().compareTo(second.id());
	}

	/**
	 * A run's weights.
	 *
	 * @param values each part's or link's weight, in the order weighed
	 * @param weightedBy what the weights are
	 */
	private record Weights(Map<PartKey, Double> values, WeightedBy weightedBy) {
	}

	/**
	 * A part's utilization in the run.
	 *
	 * @param utilization its utilization
	 * @param id the part's id
	 */
	private record PartUtilization(double utilization, String id) {
	}

}
