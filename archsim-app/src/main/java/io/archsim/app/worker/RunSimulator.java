package io.archsim.app.worker;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.simulator.RunObserver;
import io.archsim.engine.simulator.RunRequest;

/**
 * Simulates a claimed run: the engine's {@code Simulator} in the worker, stand-ins that
 * finish, fail or will not stop in its tests.
 */
@FunctionalInterface
interface RunSimulator {

	/**
	 * Simulates a design as asked, telling the observer its progress and stopping when it
	 * asks.
	 * @param document the design
	 * @param request the scenario, seed, mode and limits
	 * @param observer whoever waits for the run
	 * @return the run's report
	 */
	JsonNode simulate(JsonNode document, RunRequest request, RunObserver observer);

}
