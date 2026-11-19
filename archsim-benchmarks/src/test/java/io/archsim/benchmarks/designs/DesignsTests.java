package io.archsim.benchmarks.designs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.engine.calibration.resources.MemoryFits;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An experiment's design from its model inputs: each service's memory from the inputs of
 * its own.
 */
class DesignsTests {

	@Test
	void seeded_aDesign_isACopyAtThatSeed() {
		ObjectNode design = JsonText.object("{\"workload\": {\"rps\": 100, \"seed\": 42}}");

		ObjectNode seeded = Designs.seeded(design, 7);

		assertThat(seeded.at("/workload/seed").intValue()).isEqualTo(7);
	}

	@Test
	void seeded_aDesign_leavesItAtItsOwnSeed() {
		ObjectNode design = JsonText.object("{\"workload\": {\"rps\": 100, \"seed\": 42}}");

		Designs.seeded(design, 7);

		assertThat(design.at("/workload/seed").intValue()).isEqualTo(42);
	}

	// chain's gateway and API each take their own memory inputs
	@Test
	void design_chain_givesTheGatewayAndTheApiTheirOwnMemory() {
		ObjectNode inputs = JsonText.object("""
				{"serviceTimeMs": {"value": {"kind": "lognormal", "median": 0.18, "p95": 0.4}},
				 "clientLinkLatencyMs": {"value": 0.05},
				 "readServiceTimeMs": {"value": {"kind": "lognormal", "median": 0.5, "p95": 1.0}},
				 "writeServiceTimeMs": {"value": {"kind": "lognormal", "median": 0.7, "p95": 1.4}},
				 "commitFlushMs": {"value": {"kind": "lognormal", "median": 1.0, "p95": 2.0}},
				 "apiServiceTimeMs": {"value": 0.2}, "apiLinkLatencyMs": {"value": 0.05},
				 "serviceMemoryBaselineMb": {"value": 30.0}, "serviceMemoryPerRpsMb": {"value": 0.02},
				 "serviceMemoryPerRequestMb": {"value": 0.01},
				 "apiMemoryBaselineMb": {"value": 50.0}, "apiMemoryPerRpsMb": {"value": 0.04},
				 "apiMemoryPerRequestMb": {"value": 0.01}}""");

		ObjectNode design = Designs.design(Experiments.standard().get("chain"), inputs, JsonText.read("100"),
				Designs.Window.STEP);

		JsonNode nodes = design.at("/architecture/nodes");
		assertThat(nodes.get(1).at("/config/memoryBaselineMb").doubleValue()).isEqualTo(30.0);
		assertThat(nodes.get(2).at("/config/memoryPerRpsMb").doubleValue()).isEqualTo(0.04);
		assertThat(nodes.get(2).at("/config/memoryPerRequestMb").doubleValue())
			.isEqualTo(MemoryFits.MIN_PER_REQUEST_MB);
	}

}
