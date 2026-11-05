package io.archsim.engine.confidence;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.archsim.engine.estimate.Busiest;
import io.archsim.engine.estimate.EstimatedPart;
import io.archsim.engine.estimate.InstantEstimate;
import io.archsim.engine.estimate.PartEstimate;
import io.archsim.engine.provenance.Input;
import io.archsim.engine.technology.PartTechnology;
import io.archsim.engine.technology.TechnologyNames;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.Architecture;
import io.archsim.model.document.EdgeSpec;

/**
 * An analysis's confidence ({@code analysis_confidence}): each part weighed by its
 * estimated utilization at the peak, the busiest part as the bottleneck.
 */
public final class AnalysisConfidence {

	private final TechnologyNames names;

	/**
	 * Assesses analyses of designs made of a registry's types.
	 * @param registry the component types
	 */
	public AnalysisConfidence(ComponentRegistry registry) {
		this.names = new TechnologyNames(registry);
	}

	/**
	 * The confidence an analysis deserves.
	 * @param estimate the design's instant estimate
	 * @param inputs each input of the design, with where its value came from
	 * @param technologies each part's technology and tier
	 * @param architecture the design's architecture, to name its links
	 * @return the confidence
	 */
	public Confidence of(InstantEstimate estimate, List<Input> inputs, List<PartTechnology> technologies,
			Architecture architecture) {
		Map<PartKey, Double> weights = new LinkedHashMap<>();
		Set<String> onPath = new LinkedHashSet<>();
		for (PartEstimate part : estimate.parts()) {
			Double utilization = (part instanceof EstimatedPart estimated) ? estimated.utilization() : null;
			if (utilization != null && utilization > 0) {
				weights.put(PartKey.node(part.id()), Math.min(1.0, utilization));
			}
			if (part.onPath()) {
				onPath.add(part.id());
			}
		}
		Busiest busiest = estimate.busiest();
		return new Evidence(inputs, weights, WeightedBy.UTILIZATION, this.names.named(technologies),
				(busiest != null) ? busiest.id() : null, onPath.isEmpty() ? null : onPath, linkLabels(architecture),
				"busiest part")
			.assess();
	}

	/**
	 * Each link's name, as its caller and callee: {@code Clients → Load Balancer}.
	 * @param architecture the design's architecture
	 * @return each link's name, by its key
	 */
	public static Map<PartKey, String> linkLabels(Architecture architecture) {
		Map<String, String> names = new LinkedHashMap<>();
		architecture.nodes().forEach((node) -> names.put(node.id(), node.name()));
		Map<PartKey, String> labels = new LinkedHashMap<>();
		for (EdgeSpec edge : architecture.edges()) {
			labels.put(PartKey.edge(edge.id()), names.getOrDefault(edge.source(), edge.source()) + " → "
					+ names.getOrDefault(edge.target(), edge.target()));
		}
		return labels;
	}

}
