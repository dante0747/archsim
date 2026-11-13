package io.archsim.benchmarks.campaign;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.calibration.Calibrations;
import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.ResourceSampler;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.inputs.ModelInputs;
import io.archsim.benchmarks.load.K6;
import io.archsim.benchmarks.values.PythonValues;

/**
 * An experiment's steps on its running stack and the engine in process.
 */
final class DockerSteps implements ExperimentSteps {

	private final Instruments stack;

	private final int serviceCpus;

	private final CampaignTools tools;

	private final Calibrations calibrations;

	private final Measurement measurement;

	/**
	 * The steps on a running stack.
	 * @param stack what drives and reads it
	 * @param serviceCpus the CPUs of its service under test
	 * @param tools the engine's simulations, the prediction, k6's folder and the log
	 */
	DockerSteps(Instruments stack, int serviceCpus, CampaignTools tools) {
		this.stack = stack;
		this.serviceCpus = serviceCpus;
		this.tools = tools;
		K6 k6 = new K6(stack.compose(), tools.summaries());
		this.calibrations = new Calibrations(stack, k6, new ModelInputs(tools.simulations()), tools.log());
		this.measurement = new Measurement(stack, k6, ResourceSampler.OWN_THREAD, tools.log());
	}

	@Override
	public ObjectNode calibrate(Experiment experiment) {
		return this.calibrations.calibrate(experiment, this.serviceCpus);
	}

	@Override
	public ObjectNode predict(Experiment experiment, JsonNode calibration) {
		return this.tools.prediction().of(experiment, calibration);
	}

	@Override
	public ObjectNode measure(Experiment experiment, long rps) {
		return this.measurement.step(experiment, rps);
	}

	@Override
	public ObjectNode predictStep(Experiment experiment, JsonNode inputs, long rps) {
		return this.tools.simulations()
			.simulate(Designs.design(experiment, inputs, PythonValues.integer(rps), Designs.Window.STEP));
	}

	@Override
	public String postgresLog() {
		return this.stack.compose().result("logs", "--no-color", "postgres").output();
	}

}
