package io.archsim.benchmarks.campaign;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.ServiceHttp;
import io.archsim.benchmarks.docker.StackLifecycle;
import io.archsim.benchmarks.docker.Stacks;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.load.K6;
import io.archsim.benchmarks.load.K6Measured;
import io.archsim.benchmarks.load.Traffic;

/**
 * A stack's smoke test: its experiment's traffic at 20 req/s for 10 s, no settling, no
 * calibration and no warm-up, checked request by request ({@link SmokeProblems}). Nothing
 * is kept: no latency is shown or written, and k6's summary is deleted. The one way to
 * run a sealed experiment's stack.
 */
public final class Smoke {

	static final int RPS = 20;

	static final int SECONDS = 10;

	private static final String NAME = "smoke";

	private static final String LOG_LINES = "30";

	private final StackLifecycle stacks;

	private final Path summaries;

	private final HarnessLog log;

	/**
	 * Smoke tests of the stacks.
	 * @param stacks what starts and stops them
	 * @param summaries the folder k6's summaries land in ({@code results/.k6})
	 * @param log the log
	 */
	public Smoke(StackLifecycle stacks, Path summaries, HarnessLog log) {
		this.stacks = stacks;
		this.summaries = summaries;
		this.log = log;
	}

	/**
	 * Starts an experiment's stack, checks it and stops it.
	 * @param experiment the experiment
	 * @return the problems found; none when it passed
	 */
	public List<String> run(Experiment experiment) {
		Instruments stack = this.stacks.up(experiment.stack());
		try {
			return check(stack, experiment);
		}
		finally {
			this.stacks.down(experiment.stack());
		}
	}

	/**
	 * Starts an experiment's stack and checks it, leaving it running.
	 * @param experiment the experiment
	 * @return the problems found; none when it passed
	 */
	public List<String> runAndKeep(Experiment experiment) {
		return check(this.stacks.up(experiment.stack()), experiment);
	}

	private List<String> check(Instruments stack, Experiment experiment) {
		stack.services().post(ServiceHttp.API + "/reset");
		K6 k6 = new K6(stack.compose(), this.summaries);
		JsonNode summary;
		try {
			summary = k6.run(Traffic.of(experiment, experiment.mix(), RPS), new K6.Window(0, SECONDS), NAME);
		}
		finally {
			k6.forget(NAME);
		}
		List<String> problems = SmokeProblems.of(experiment, K6Measured.of(summary, SECONDS),
				stack.services().get(ServiceHttp.API + "/stats"), RPS * SECONDS);
		if (!problems.isEmpty()) {
			List<String> arguments = new ArrayList<>(List.of("logs", "--no-color", "--tail", LOG_LINES));
			arguments.addAll(Stacks.standard().containers(experiment.stack()));
			this.log.text(stack.compose().result(arguments.toArray(String[]::new)).output());
		}
		return problems;
	}

}
