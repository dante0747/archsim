package io.archsim.benchmarks.inputs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.experiments.Experiment;

/**
 * The model at one load: an experiment's design from some inputs, simulated
 * ({@link Designs#design}, then
 * {@link io.archsim.benchmarks.designs.Simulations#simulate}).
 */
@FunctionalInterface
interface DesignSimulation {

	/**
	 * The simulation's summary.
	 * @param experiment the experiment
	 * @param inputs the model inputs, each with its value
	 * @param rps the offered load
	 * @param window the simulated seconds
	 * @return its latency, success, throughput and resources
	 */
	ObjectNode at(Experiment experiment, JsonNode inputs, JsonNode rps, Designs.Window window);

}
