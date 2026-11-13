package io.archsim.benchmarks;

import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import io.archsim.benchmarks.campaign.Smoke;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;

/**
 * {@code smoke EXPERIMENT}: starts an experiment's stack, sends 20 req/s for 10 s and
 * checks that every request succeeded and was counted, keeping nothing; the one command
 * that starts a sealed experiment's stack.
 */
@Command(name = "smoke",
		description = "start an experiment's stack, send 20 req/s for 10 s and check every request succeeded and "
				+ "was counted (keeps nothing)",
		mixinStandardHelpOptions = true, version = Benchmarks.VERSION)
final class SmokeCommand implements Callable<Integer>, HarnessRules.JavaEngineOnly {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Parameters(paramLabel = "EXPERIMENT", description = "the experiment to smoke-test")
	private String experiment = "";

	@Option(names = "--keep", description = "leave the stack running afterwards")
	private boolean keep;

	private final HarnessSetup setup;

	SmokeCommand(HarnessSetup setup) {
		this.setup = setup;
	}

	@Override
	public Integer call() {
		Experiments experiments = Experiments.standard();
		List<String> names = experiments.all().stream().map(Experiment::id).toList();
		if (!names.contains(this.experiment)) {
			throw new ParameterException(this.spec.commandLine(),
					"smoke: no experiment '" + this.experiment + "' (one of " + String.join(", ", names) + ")");
		}
		Smoke smoke = new Smoke(this.setup.stacks(), this.setup.layout().k6Summaries(), this.setup.log());
		Experiment smoked = experiments.get(this.experiment);
		List<String> problems = this.keep ? smoke.runAndKeep(smoked) : smoke.run(smoked);
		problems.forEach((problem) -> this.setup.log().line("smoke " + this.experiment + ": " + problem));
		this.setup.log().line("smoke " + this.experiment + ": " + (problems.isEmpty() ? "passed" : "FAILED"));
		return problems.isEmpty() ? 0 : 1;
	}

}
