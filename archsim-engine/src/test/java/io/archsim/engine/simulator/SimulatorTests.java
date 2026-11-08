package io.archsim.engine.simulator;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The simulator beyond the frozen engine's recorded runs ({@link SimulationCasesTests}):
 * what it does not simulate yet, it refuses.
 */
class SimulatorTests {

	private static final Simulator SIMULATOR = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void run_aKubernetesCluster_isRefused() {
		JsonNode design = design("""
				{"id": "part", "type": "service", "config": {"platform": "kubernetes"}},
				{"id": "cluster", "type": "kubernetes_cluster", "config": {}}""");

		assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> SIMULATOR.run(design, null))
			.withMessage("the Java simulator does not simulate a kubernetes_cluster yet");
	}

	// clients calling a part, among others
	private static JsonNode design(String parts) {
		return JsonMapper.builder().build().readTree("""
				{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "case"},
				 "architecture": {"autoSize": false,
				  "nodes": [{"id": "clients", "type": "traffic_source", "config": {}}, %s],
				  "edges": [{"id": "e", "source": "clients", "target": "part"}]},
				 "workload": {"rps": 10, "durationS": 2, "warmupS": 1}}
				""".formatted(parts));
	}

}
