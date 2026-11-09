package io.archsim.engine.calibration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.run.SimulationLimitException;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A calibration's replay on the Java simulator: the whole run of the design as drawn,
 * within the replay's limits, or the engine's when it has none.
 */
class ReplaySimulatorTests {

	private static final Simulator SIMULATOR = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ObjectNode DESIGN = design("""
			{"seed": 7, "warmupS": 1, "durationS": 6, "rps": 100, "arrivalProcess": "deterministic"}""");

	/**
	 * Steady and just long enough for a quick check to answer from a sample: a 2 s
	 * warm-up and a 10 s sample are half its 24 s.
	 */
	private static final ObjectNode LONG_AND_STEADY = design("""
			{"seed": 7, "warmupS": 2, "durationS": 24, "rps": 5000, "arrivalProcess": "deterministic"}""");

	private static final SimulationLimits ROOMY = new SimulationLimits(100_000, 60.0);

	private final ReplaySimulator replay = ReplaySimulator.of(SIMULATOR);

	@Test
	void of_aDesignAQuickCheckWouldSample_isSimulatedWhole() {
		JsonNode report = this.replay.simulate(LONG_AND_STEADY, null);

		// a quick check writes how it answered; a whole run asked for says nothing
		assertThat(report.has("simulationMode")).isFalse();
	}

	@Test
	void of_aDesign_isLeftUnchanged() {
		ObjectNode design = DESIGN.deepCopy();

		this.replay.simulate(design, ROOMY);

		assertThat(design).isEqualTo(DESIGN);
	}

	@Test
	void of_aDesign_isSimulatedWholeAsDrawn() {
		JsonNode report = this.replay.simulate(DESIGN, ROOMY);

		assertThat(timeless(report)).isEqualTo(timeless(SIMULATOR.run(DESIGN, RunRequest.of(null).withLimits(ROOMY))));
	}

	@Test
	void of_noLimits_simulatesWithinTheEnginesOwn() {
		JsonNode report = this.replay.simulate(DESIGN, null);

		assertThat(timeless(report)).isEqualTo(timeless(SIMULATOR.run(DESIGN, RunRequest.of(null))));
	}

	@Test
	void of_aRunOverItsLimits_isRefusedInTheEnginesWords() {
		SimulationLimits tight = new SimulationLimits(10, 60.0);

		assertThatExceptionOfType(SimulationLimitException.class).isThrownBy(() -> this.replay.simulate(DESIGN, tight));
	}

	/**
	 * Clients calling an API that answers in 20 ms.
	 */
	private static ObjectNode design(String workload) {
		return (ObjectNode) JSON.readTree("""
				{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "replay"},
				 "architecture": {"autoSize": false,
				   "nodes": [{"id": "clients", "type": "traffic_source", "config": {}},
				             {"id": "api", "type": "external_api",
				              "config": {"latencyMs": {"kind": "constant", "value": 20}}}],
				   "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": %s}""".formatted(workload));
	}

	/**
	 * A report without the wall-clock time it took, the one figure two runs of a design
	 * differ by.
	 */
	private static JsonNode timeless(JsonNode report) {
		ObjectNode copy = (ObjectNode) report.deepCopy();
		((ObjectNode) copy.get("provenance")).remove("wallSeconds");
		return copy;
	}

}
