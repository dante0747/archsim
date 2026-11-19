package io.archsim.benchmarks.inputs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;

/**
 * A held-out experiment's inputs and its design from two calibration passes
 * ({@link HeldOutPasses}), for the tests of both held-out sets: neither needs a kept
 * calibration, which a sealed experiment does not have.
 */
final class HeldOutDesigns {

	private static final ModelInputs INPUTS = new ModelInputs(Simulations.onTheEngine());

	private static final Designs.Window BRIEF = new Designs.Window(1, 3);

	private HeldOutDesigns() {
	}

	/**
	 * The inputs derived from an experiment's two passes.
	 * @param experiment the experiment's name
	 * @return the inputs, each with its value and how it was found
	 */
	static ObjectNode inputs(String experiment) {
		Experiment heldOut = Experiments.standard().get(experiment);
		return INPUTS.of(heldOut, HeldOutPasses.of(heldOut));
	}

	/**
	 * An experiment's design at 100 req/s, briefly, with a client latency of 0.2 ms.
	 * @param experiment the experiment's name
	 * @return the design
	 */
	static ObjectNode design(String experiment) {
		ObjectNode inputs = inputs(experiment);
		((ObjectNode) inputs.get("clientLinkLatencyMs")).put("value", 0.2);
		return Designs.design(Experiments.standard().get(experiment), inputs, JsonText.read("100"), BRIEF);
	}

	/**
	 * A node's settings.
	 * @param design the design
	 * @param id the node's id
	 * @return its {@code config}
	 */
	static JsonNode node(JsonNode design, String id) {
		return design.at("/architecture/nodes")
			.valueStream()
			.filter((node) -> node.get("id").stringValue().equals(id))
			.findFirst()
			.orElseThrow()
			.get("config");
	}

}
