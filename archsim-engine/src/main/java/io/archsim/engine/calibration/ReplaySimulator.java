package io.archsim.engine.calibration;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.simulation.SimulationLimits;

/**
 * The simulation a calibration replays the calibrated design with, to check it against
 * what was observed. It is a seam because the simulation kernel is ported in stage 4:
 * until then the app runs the Python simulator, and the golden files' recorded reports
 * stand in for it in the tests.
 */
@FunctionalInterface
public interface ReplaySimulator {

	/**
	 * Simulates a design.
	 * @param document the design
	 * @param limits the run's limits; {@code null} for none
	 * @return the run's report
	 */
	JsonNode simulate(ObjectNode document, @Nullable SimulationLimits limits);

}
