package io.archsim.benchmarks.calibration;

import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.ResourceSampler;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.inputs.ModelInputs;
import io.archsim.benchmarks.load.K6;

/**
 * Calibrations on a running stack: its passes run through k6 and read from the services,
 * PostgreSQL, Redis and the cgroups, the inputs derived and anchored on the engine.
 */
public final class Calibrations {

	private final Instruments stack;

	private final K6 k6;

	private final ModelInputs inputs;

	private final HarnessLog log;

	/**
	 * Calibrations on a stack.
	 * @param stack what drives and reads it
	 * @param k6 k6 on it
	 * @param inputs what derives and anchors the model inputs
	 * @param log the log
	 */
	public Calibrations(Instruments stack, K6 k6, ModelInputs inputs, HarnessLog log) {
		this.stack = stack;
		this.k6 = k6;
		this.inputs = inputs;
		this.log = log;
	}

	/**
	 * Calibrates an experiment.
	 * @param experiment the experiment
	 * @param serviceCpus the CPUs of its service under test, its cpuset's
	 * @return its {@code calibration.json}
	 */
	public ObjectNode calibrate(Experiment experiment, int serviceCpus) {
		return new Calibration(new DockerPasses(this.stack, this.k6, ResourceSampler.OWN_THREAD, this.log),
				new Staircase(serviceCpus), this.inputs::anchored, this.log)
			.calibrate(experiment);
	}

}
