package io.archsim.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.provenance.Input;
import io.archsim.engine.provenance.InputKind;
import io.archsim.engine.provenance.InputProvenances;
import io.archsim.engine.technology.TechnologyTiers;
import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.NodeSpec;
import io.archsim.parity.GoldenEntry;

/**
 * Where each input came from, each part's technology tier, and the model a run simulates.
 */
final class InputFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.provenance.input_provenance", InputFunctions::inputProvenance),
			Map.entry("archsim_engine.provenance.provenance_counts", InputFunctions::provenanceCounts),
			Map.entry("archsim_engine.registry.ComponentRegistry.technology_tiers", InputFunctions::technologyTiers),
			Map.entry("archsim_engine.scenarios.simulation_input", InputFunctions::simulationInput));

	private static final InputProvenances INPUTS = new InputProvenances(StandardComponents.registry());

	private static final TechnologyTiers TIERS = new TechnologyTiers(StandardComponents.registry());

	private InputFunctions() {
	}

	private static JsonNode inputProvenance(GoldenEntry entry) {
		ArrayNode output = GoldenJson.NODES.arrayNode();
		INPUTS.of(DesignDocuments.parse(entry.input("doc"))).forEach((input) -> output.add(RecordCodec.write(input)));
		return output;
	}

	private static JsonNode provenanceCounts(GoldenEntry entry) {
		ObjectNode output = GoldenJson.NODES.objectNode();
		List<Input> inputs = new ArrayList<>();
		entry.input("inputs").forEach((input) -> inputs.add(RecordCodec.read(input, Input.class)));
		Map<InputKind, Integer> counts = InputProvenances.counts(inputs);
		counts.forEach((kind, count) -> output.put(kind.json(), count));
		return output;
	}

	private static JsonNode technologyTiers(GoldenEntry entry) {
		List<NodeSpec> nodes = new ArrayList<>();
		entry.input("nodes").forEach((node) -> nodes.add(RecordCodec.read(node, NodeSpec.class)));
		ArrayNode output = GoldenJson.NODES.arrayNode();
		TIERS.of(nodes).stream().map(RecordCodec::write).forEach(output::add);
		return output;
	}

	private static JsonNode simulationInput(GoldenEntry entry) {
		return DesignDocuments.simulationInput(DesignDocuments.parse(entry.input("resolved")));
	}

}
