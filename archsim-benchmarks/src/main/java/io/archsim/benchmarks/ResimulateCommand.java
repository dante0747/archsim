package io.archsim.benchmarks;

import java.util.List;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.resimulation.CheckStamp;
import io.archsim.benchmarks.resimulation.DevLabels;
import io.archsim.benchmarks.resimulation.Resimulation;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.KeptRuns;

/**
 * {@code resimulate}: checks today's model against every kept run, in-sample, and writes
 * each check under {@code results/dev/}, without Docker.
 */
@Command(name = "resimulate",
		description = "check today's model against every kept run (in-sample, written under results/dev/; no Docker)",
		mixinStandardHelpOptions = true, version = Benchmarks.VERSION)
final class ResimulateCommand implements Callable<Integer>, HarnessRules.JavaEngineOnly {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Option(names = "--only", paramLabel = "EXPERIMENT", description = "check one experiment's kept runs only")
	private @Nullable String only;

	@Option(names = "--label",
			description = "write the checks under results/dev/LABEL/, a label that starts with the engine's "
					+ "(1.0.0-port, say) and names no other engine; the engine's own by default. It writes over that "
					+ "folder's checks of the same calibrations, committed or not")
	private @Nullable String label;

	private final HarnessSetup setup;

	private final RepositoryLayout layout;

	private final HarnessLog log;

	ResimulateCommand(HarnessSetup setup) {
		this.setup = setup;
		this.layout = setup.layout();
		this.log = setup.log();
	}

	/**
	 * Checks the kept runs and writes their checks.
	 * @return 0 once they are written
	 * @throws ParameterException when the label names another engine or no experiment has
	 * the name {@code --only} gives, before anything is simulated
	 */
	@Override
	public Integer call() {
		String folder;
		List<KeptRun> runs;
		try {
			folder = DevLabels.of(this.label);
			runs = runs(this.only);
		}
		catch (IllegalArgumentException ex) {
			throw new ParameterException(this.spec.commandLine(), ex.getMessage(), ex);
		}
		CheckStamp stamp = CheckStamp.of(folder, this.layout.root(), this.log.clock());
		new Resimulation(this.layout.results(), this.setup.modelInputs(Simulations.onTheEngine()), stamp, this.log)
			.resimulate(runs)
			.forEach((written) -> this.log.line("wrote " + this.layout.name(written)));
		return 0;
	}

	// every kept run, or one experiment's, which must be one of the experiments
	private List<KeptRun> runs(@Nullable String experiment) {
		List<KeptRun> kept = KeptRuns.of(this.layout.results());
		if (experiment == null) {
			return kept;
		}
		String id = Experiments.standard().get(experiment).id();
		return kept.stream().filter((run) -> run.experiment().equals(id)).toList();
	}

}
