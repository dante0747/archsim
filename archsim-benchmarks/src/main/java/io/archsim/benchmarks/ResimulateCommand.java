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
import io.archsim.benchmarks.history.GitRepository;
import io.archsim.benchmarks.provenance.EngineBuild;
import io.archsim.benchmarks.provenance.MachineName;
import io.archsim.benchmarks.provenance.Provenance;
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
final class ResimulateCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Option(names = "--only", paramLabel = "EXPERIMENT", description = "check one experiment's kept runs only")
	private @Nullable String only;

	@Option(names = "--label",
			description = "write the checks under results/dev/LABEL/, a label that starts with the engine's "
					+ "(1.0.0-port, say) and names no other engine; the engine's own by default. It writes over that "
					+ "folder's checks of the same calibrations, committed or not")
	private @Nullable String label;

	private final RepositoryLayout layout;

	private final HarnessLog log;

	ResimulateCommand(RepositoryLayout layout, HarnessLog log) {
		this.layout = layout;
		this.log = log;
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
		Provenance provenance = new Provenance(new GitRepository(this.layout.root()), MachineName.current(),
				EngineBuild.current().orElse(null));
		CheckStamp stamp = new CheckStamp(folder, provenance.of(), this.log.clock());
		new Resimulation(this.layout.results(), Simulations.onTheEngine(), stamp, this.log).resimulate(runs)
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
