package io.archsim.engine.simulator;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.run.SimulationLimitException;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * What the recorded cases cannot show: a run stopped by its wall-clock limit, at the
 * simulated time the Python engine stops it (its message taken from the frozen engine
 * with a limit of 0 s). {@code SimulationCasesTests} checks the reports.
 */
class SimulatorTests {

	private static final long NANOS_PER_SECOND = 1_000_000_000L;

	private static final RunRequest NO_TIME = RunRequest.of(null).withLimits(new SimulationLimits(2_000_000, 0.0));

	// each look at the clock finds a second gone
	private final AtomicLong nanos = new AtomicLong();

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped(),
			() -> this.nanos.getAndAdd(NANOS_PER_SECOND));

	@Test
	void run_pastItsWallClockLimitInABusyChunk_isStoppedAfterAFewThousandEvents() {
		// the first 3 s chunk holds 3,000 requests: the check after 4,096 events stops it
		JsonNode design = design("""
				{"seed": 7, "warmupS": 1, "durationS": 6, "drainS": 600, "rps": 1000,
				 "arrivalProcess": "deterministic"}""");

		assertThatExceptionOfType(SimulationLimitException.class).isThrownBy(() -> this.simulator.run(design, NO_TIME))
			.withMessage("Simulation exceeded the wall-clock limit of 0 s at simulated time 0.4 s. "
					+ "Reduce RPS, duration, fan-out or model size.");
	}

	@Test
	void run_pastItsWallClockLimitInAQuietChunk_isStoppedAtTheChunksEnd() {
		JsonNode design = design("""
				{"seed": 7, "warmupS": 1, "durationS": 6, "drainS": 600, "rps": 100,
				 "arrivalProcess": "deterministic"}""");

		assertThatExceptionOfType(SimulationLimitException.class).isThrownBy(() -> this.simulator.run(design, NO_TIME))
			.withMessage("Simulation exceeded the wall-clock limit of 0 s at simulated time 3.0 s. "
					+ "Reduce RPS, duration, fan-out or model size.");
	}

	@Test
	void run_aQuickCheckPastItsWallClockLimit_isStoppedInItsSample() {
		JsonNode design = design("""
				{"seed": 7, "warmupS": 2, "durationS": 30, "rps": 5000, "arrivalProcess": "deterministic"}""");

		assertThatExceptionOfType(SimulationLimitException.class)
			.isThrownBy(() -> this.simulator.run(design, NO_TIME.withMode(SimulationMode.SAMPLED)))
			.withMessage("Simulation exceeded the wall-clock limit of 0 s at simulated time 0.1 s. "
					+ "Reduce RPS, duration, fan-out or model size.");
	}

	// clients calling an API that answers in 20 ms, with this workload
	private static JsonNode design(String workload) {
		return JsonMapper.builder().build().readTree("""
				{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "case"},
				 "architecture": {"autoSize": false,
				   "nodes": [{"id": "clients", "type": "traffic_source", "config": {}},
				             {"id": "api", "type": "external_api",
				              "config": {"latencyMs": {"kind": "constant", "value": 20}}}],
				   "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
				 "workload": %s}""".formatted(workload));
	}

}
