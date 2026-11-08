package io.archsim.engine.simulator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			database | {}                                   | a database
			service  | {"platform": "serverless"}           | a serverless service
			service  | {"autoscaling": true}                | an autoscaling service
			service  | {"cpuCores": 4, "siblingSpeed": 0.6} | hyperthreads
			""")
	void run_aPartNotPortedYet_isRefused(String type, String config, String what) {
		JsonNode design = design("", "{\"id\": \"part\", \"type\": \"" + type + "\", \"config\": " + config + "}");

		assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> SIMULATOR.run(design, null))
			.withMessage("the Java simulator does not simulate " + what + " yet");
	}

	@Test
	void run_aDesignOnAHostMachine_isRefusedUntilTheHostIsPorted() {
		JsonNode design = design("\"host\": {\"physicalCores\": 4},",
				"{\"id\": \"part\", \"type\": \"external_api\", \"config\": {}}");

		assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> SIMULATOR.run(design, null))
			.withMessage("the Java simulator does not simulate a host machine yet");
	}

	// clients calling one part
	private static JsonNode design(String host, String part) {
		return JsonMapper.builder().build().readTree("""
				{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "case"},
				 "architecture": {"autoSize": false, %s
				  "nodes": [{"id": "clients", "type": "traffic_source", "config": {}}, %s],
				  "edges": [{"id": "e", "source": "clients", "target": "part"}]},
				 "workload": {"rps": 10, "durationS": 2, "warmupS": 1}}
				""".formatted(host, part));
	}

}
