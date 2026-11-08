package io.archsim.engine.simulator;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The simulator beyond the frozen engine's recorded runs ({@link SimulationCasesTests}).
 */
class SimulatorTests {

	private static final Simulator SIMULATOR = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void run_aPartNotPortedYet_isRefused() {
		JsonNode design = JsonMapper.builder().build().readTree("""
				{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "case"},
				 "architecture": {"autoSize": false,
				  "nodes": [{"id": "clients", "type": "traffic_source", "config": {}},
				            {"id": "svc", "type": "service", "config": {}}],
				  "edges": [{"id": "e", "source": "clients", "target": "svc"}]},
				 "workload": {"rps": 10, "durationS": 2, "warmupS": 1}}
				""");

		assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> SIMULATOR.run(design))
			.withMessage("the Java simulator does not simulate a service yet");
	}

}
