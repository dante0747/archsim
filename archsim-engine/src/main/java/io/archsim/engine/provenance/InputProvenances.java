package io.archsim.engine.provenance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.catalog.ComponentFacts;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;

/**
 * Where every numeric input of a design came from. An input is measured when the
 * document's provenance says so (calibration writes it) and the input still has the value
 * it recorded; documented when it is what the part's technology, or a preset, fills in
 * and the vendor's documentation states it; illustrative when it is ArchSim's default or
 * another number a profile or preset fills in; and assumed otherwise. A measured input
 * whose value has changed since is classified as if it had no record, with a note saying
 * so.
 */
public final class InputProvenances {

	private final NodeInputs nodes;

	private final EdgeInputs edges;

	/**
	 * Classifies the inputs of designs made of a registry's types.
	 * @param registry the component types
	 */
	public InputProvenances(ComponentRegistry registry) {
		ComponentFacts facts = new ComponentFacts(registry);
		this.nodes = new NodeInputs(registry, facts);
		this.edges = new EdgeInputs(facts);
	}

	/**
	 * Every performance input of a design (settings marked as assumptions, link
	 * latencies, the workload) and every input it records provenance for.
	 * @param document the design, as a run simulates it
	 * @return the inputs: the parts' in the design's order, then the links', then the
	 * workload's
	 */
	public List<Input> of(ArchitectureDocument document) {
		List<Input> inputs = new ArrayList<>();
		for (NodeSpec node : document.architecture().nodes()) {
			inputs.addAll(this.nodes.of(document, node));
		}
		for (EdgeSpec edge : document.architecture().edges()) {
			inputs.add(this.edges.of(document, edge));
		}
		ObjectNode workload = RecordCodec.write(document.workload());
		for (WorkloadInput field : WorkloadInput.values()) {
			inputs.add(field.of(document, workload));
		}
		return List.copyOf(inputs);
	}

	/**
	 * How many inputs are of each kind.
	 * @param inputs the inputs
	 * @return the count of each kind, every kind included, in the order reports count
	 * them
	 */
	public static Map<InputKind, Integer> counts(List<Input> inputs) {
		Map<InputKind, Integer> counts = new EnumMap<>(InputKind.class);
		for (InputKind kind : InputKind.values()) {
			counts.put(kind, 0);
		}
		inputs.forEach((input) -> counts.merge(input.provenance().kind(), 1, Integer::sum));
		return Collections.unmodifiableMap(counts);
	}

}
