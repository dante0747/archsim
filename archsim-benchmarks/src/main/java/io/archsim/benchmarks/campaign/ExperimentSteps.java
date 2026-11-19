package io.archsim.benchmarks.campaign;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Experiment;

/**
 * What an experiment's run does on its stack and the engine: calibrate, predict, measure
 * a step, predict one more step, and read PostgreSQL's log. A test plays them without
 * Docker.
 */
interface ExperimentSteps {

	/**
	 * Calibrates the experiment.
	 * @param experiment the experiment
	 * @return its {@code calibration.json}
	 */
	ObjectNode calibrate(Experiment experiment);

	/**
	 * Predicts the experiment from its calibration.
	 * @param experiment the experiment
	 * @param calibration its calibration
	 * @return its {@code predictions.json}
	 */
	ObjectNode predict(Experiment experiment, JsonNode calibration);

	/**
	 * Measures a step of the ladder.
	 * @param experiment the experiment
	 * @param rps the step's offered load
	 * @return the step, as {@code measured.json} holds it
	 */
	ObjectNode measure(Experiment experiment, long rps);

	/**
	 * Predicts one more step: above the ladder, or where the run closes in on the knee.
	 * @param experiment the experiment
	 * @param inputs the calibration's inputs
	 * @param rps the step's offered load
	 * @return the step, as {@code predictions.json} holds it
	 */
	ObjectNode predictStep(Experiment experiment, JsonNode inputs, long rps);

	/**
	 * What PostgreSQL logged on the stack.
	 * @return its log
	 */
	String postgresLog();

}
