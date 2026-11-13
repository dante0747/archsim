package io.archsim.benchmarks.calibration;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.experiments.Experiment;

/**
 * The passes a calibration runs on an experiment's stack: settling it, its rest, and one
 * pass of a mix at a rate. A test plays them without Docker.
 */
interface CalibrationPasses {

	/**
	 * Settles the stack after its data load: VACUUM ANALYZE and a checkpoint, then a
	 * discarded warm-up at 50 req/s.
	 * @param experiment the experiment
	 * @return what was done, and when
	 */
	JsonNode settle(Experiment experiment);

	/**
	 * The stack at rest: each container's CPU, the service's own, and each container's
	 * working set.
	 * @param experiment the experiment
	 * @return the idle pass
	 */
	JsonNode idle(Experiment experiment);

	/**
	 * One pass: a warm-up at the rate, the statistics reset, then 30 s recorded.
	 * @param experiment the experiment
	 * @param mix the requests' mix
	 * @param rate requests per second
	 * @param label the pass's name in the staircase ({@code up 2}, {@code write_async 1})
	 * @return the pass
	 */
	JsonNode run(Experiment experiment, String mix, JsonNode rate, String label);

}
