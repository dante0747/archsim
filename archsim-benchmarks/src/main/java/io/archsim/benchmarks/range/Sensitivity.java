package io.archsim.benchmarks.range;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.parallel.InParallel;
import io.archsim.engine.numeric.PythonRound;

/**
 * How much the knee moves with each input that varies between calibrations: the knee with
 * the input {@link #STEP} up in log terms, against the unperturbed knee, each searched
 * from the knee already found on shorter simulations. A linear reading in log terms,
 * taken on the side where the input costs more: where the knee switches between two
 * limits it reads the one that binds as the input grows.
 */
public final class Sensitivity {

	/**
	 * How far each input is moved up, in log terms (x e^0.2 = +22%): several times its
	 * run-to-run variation, so the knee search's 1% resolution moves the slope by at most
	 * about 0.05.
	 */
	static final double STEP = 0.2;

	/**
	 * The simulated seconds of each load in the sensitivity's searches: a third of a
	 * measured step's, since a search past the knee is slow to simulate. The unperturbed
	 * knee is searched the same way, so the slope compares like with like.
	 */
	static final Designs.Window WINDOW = new Designs.Window(2, 8);

	private static final double UP = StrictMath.exp(STEP);

	private static final int DECIMALS = 3;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private Sensitivity() {
	}

	/**
	 * The knee's sensitivity to each varied input, its searches run in parallel.
	 * @param simulations the engine's simulations
	 * @param experiment the experiment
	 * @param inputs the inputs the knee was found with
	 * @param knee the knee, where each search starts
	 * @return d ln(knee) / d ln(input) for each input ({@link #fromKnees})
	 */
	public static ObjectNode of(Simulations simulations, Experiment experiment, JsonNode inputs, JsonNode knee) {
		return fromKnees(inputs, InParallel.map(jobs(inputs), (job) -> kneeNear(simulations, experiment, job, knee)));
	}

	/**
	 * How many inputs the sensitivity varies.
	 * @param inputs the inputs
	 * @return how many of them vary between calibrations
	 */
	public static int inputsVaried(JsonNode inputs) {
		return RangeInputs.keys(inputs).size();
	}

	/**
	 * The inputs of the searches the sensitivity needs: as they are, then each input of
	 * {@link RangeInputs#keys} {@link #STEP} up.
	 * @param inputs the inputs
	 * @return the inputs of each search, in that order
	 */
	static List<JsonNode> jobs(JsonNode inputs) {
		List<JsonNode> jobs = new ArrayList<>();
		jobs.add(inputs.deepCopy());
		RangeInputs.keys(inputs).forEach((key) -> jobs.add(RangeInputs.perturbed(inputs, key, UP)));
		return jobs;
	}

	/**
	 * The sensitivity from the knees of {@link #jobs}, in its order: -1 when the knee
	 * falls by the share the input rose.
	 * @param inputs the inputs
	 * @param knees each search's knee
	 * @return d ln(knee) / d ln(input) for each input, to 3 decimals; 0 when a knee is
	 * none
	 */
	static ObjectNode fromKnees(JsonNode inputs, List<Double> knees) {
		double base = knees.getFirst();
		ObjectNode sensitivity = NODES.objectNode();
		List<String> keys = RangeInputs.keys(inputs);
		for (int index = 0; index < keys.size(); index++) {
			double up = knees.get(1 + index);
			double slope = (up > 0 && base > 0) ? (StrictMath.log(up) - StrictMath.log(base)) / STEP : 0.0;
			sensitivity.put(keys.get(index), PythonRound.round(slope, DECIMALS));
		}
		return sensitivity;
	}

	/**
	 * The knee of slightly different inputs, searched from a knee already found, on the
	 * sensitivity's shorter simulations.
	 * @param simulations the engine's simulations
	 * @param experiment the experiment
	 * @param inputs the inputs
	 * @param near the knee already found
	 * @return the knee (req/s)
	 */
	static double kneeNear(Simulations simulations, Experiment experiment, JsonNode inputs, JsonNode near) {
		return simulations.knee(experiment, inputs, near, WINDOW).get("kneeRps").doubleValue();
	}

}
