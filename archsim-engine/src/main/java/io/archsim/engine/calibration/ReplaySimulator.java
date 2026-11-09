package io.archsim.engine.calibration;

import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.Simulator;

/**
 * The simulation a calibration replays the calibrated design with, to check it against
 * what was observed: the Java simulator's ({@link #of}), and in the tests of a
 * calibration's arithmetic, the reports the golden files recorded.
 */
@FunctionalInterface
public interface ReplaySimulator {

	/**
	 * Simulates a design, leaving it unchanged.
	 * @param document the design
	 * @param limits the run's limits; {@code null} for the simulator's own
	 * ({@link SimulationLimits#DEFAULT} on the Java simulator)
	 * @return the run's report
	 * @throws io.archsim.model.issue.DocumentInvalidException when the design cannot be
	 * simulated
	 * @throws io.archsim.engine.run.SimulationLimitException when the run is over its
	 * limits
	 */
	JsonNode simulate(ObjectNode document, @Nullable SimulationLimits limits);

	/**
	 * Replays on a simulator: the whole run of the design as drawn, with its workload's
	 * seed, within the replay's limits, or the engine's own when it has none, as the
	 * Python engine's {@code run_simulation} replayed it.
	 * @param simulator the simulator
	 * @return the replay
	 */
	static ReplaySimulator of(Simulator simulator) {
		return (document, limits) -> simulator.run(document,
				RunRequest.of(null).withLimits(Objects.requireNonNullElse(limits, SimulationLimits.DEFAULT)));
	}

}
