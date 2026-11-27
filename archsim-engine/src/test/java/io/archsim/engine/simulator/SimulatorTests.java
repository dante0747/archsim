package io.archsim.engine.simulator;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.run.SimulationLimitException;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * What the recorded cases cannot show: a run stopped by its wall-clock limit, at the
 * simulated time the Python engine stops it (its message taken from the frozen engine
 * with a limit of 0 s), and a design with a number that is not finite refused before
 * anything is simulated. {@code SimulationCasesTests} checks the reports.
 */
class SimulatorTests {

	private static final long NANOS_PER_SECOND = 1_000_000_000L;

	/** Long for a refusal, which takes milliseconds; short for a run that never ends. */
	private static final long REFUSED_WITHIN_S = 30;

	private static final RunRequest NO_TIME = RunRequest.of(null).withLimits(new SimulationLimits(2_000_000, 0.0));

	// each look at the clock finds a second gone
	private final AtomicLong nanos = new AtomicLong();

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped(),
			() -> this.nanos.getAndAdd(NANOS_PER_SECOND));

	// refused when the design is read, in milliseconds: read as a number, such a time
	// made the rate NaN, and no comparison with NaN ended the arrival loop
	@Test
	@Timeout(value = REFUSED_WITHIN_S, threadMode = ThreadMode.SEPARATE_THREAD)
	void run_aTraceTimeThatIsNotANumber_isRefusedBeforeItRuns() {
		JsonNode design = SmallDesigns.clientsCallingAnApi("""
				{"seed": 7, "warmupS": 1, "durationS": 10,
				 "pattern": {"kind": "trace", "points": [[0, 50], ["nan", 50]]}}""");

		assertThatExceptionOfType(DocumentInvalidException.class)
			.isThrownBy(() -> this.simulator.run(design, RunRequest.of(null)));
	}

	@Test
	void run_pastItsWallClockLimitInABusyChunk_isStoppedAfterAFewThousandEvents() {
		// the first 3 s chunk holds 3,000 requests: the check after 4,096 events stops it
		JsonNode design = SmallDesigns.clientsCallingAnApi(SmallDesigns.BUSY);

		assertThatExceptionOfType(SimulationLimitException.class).isThrownBy(() -> this.simulator.run(design, NO_TIME))
			.withMessage("Simulation exceeded the wall-clock limit of 0 s at simulated time 0.4 s. "
					+ "Reduce RPS, duration, fan-out or model size.");
	}

	@Test
	void run_pastItsWallClockLimitInAQuietChunk_isStoppedAtTheChunksEnd() {
		JsonNode design = SmallDesigns.clientsCallingAnApi(SmallDesigns.QUIET);

		assertThatExceptionOfType(SimulationLimitException.class).isThrownBy(() -> this.simulator.run(design, NO_TIME))
			.withMessage("Simulation exceeded the wall-clock limit of 0 s at simulated time 3.0 s. "
					+ "Reduce RPS, duration, fan-out or model size.");
	}

	@Test
	void run_aQuickCheckPastItsWallClockLimit_isStoppedInItsSample() {
		JsonNode design = SmallDesigns.clientsCallingAnApi("""
				{"seed": 7, "warmupS": 2, "durationS": 30, "rps": 5000, "arrivalProcess": "deterministic"}""");

		assertThatExceptionOfType(SimulationLimitException.class)
			.isThrownBy(() -> this.simulator.run(design, NO_TIME.withMode(SimulationMode.SAMPLED)))
			.withMessage("Simulation exceeded the wall-clock limit of 0 s at simulated time 0.1 s. "
					+ "Reduce RPS, duration, fan-out or model size.");
	}

}
