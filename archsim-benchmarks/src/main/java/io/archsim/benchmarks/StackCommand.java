package io.archsim.benchmarks;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

import io.archsim.benchmarks.campaign.Campaign;
import io.archsim.benchmarks.campaign.CampaignMachine;
import io.archsim.benchmarks.campaign.CampaignTools;
import io.archsim.benchmarks.campaign.Prediction;
import io.archsim.benchmarks.campaign.Seals;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.history.GitRepository;
import io.archsim.benchmarks.inputs.ModelInputs;
import io.archsim.benchmarks.machine.MachineFit;
import io.archsim.benchmarks.provenance.EngineBuild;
import io.archsim.benchmarks.provenance.MachineName;
import io.archsim.benchmarks.provenance.Provenance;

/**
 * A stack's command ({@code postgres}, {@code cache}, ...) or {@code all}: each of its
 * experiments calibrated, predicted, measured and compared on Docker. A held-out
 * experiment runs only on a frozen engine with nothing uncommitted; asked for by its
 * stack it stops the command before any stack starts, and {@code all} leaves it out.
 */
@Command(
		description = "calibrate, predict, measure and compare the stack's experiments on Docker (held-out "
				+ "experiments only with a frozen engine)",
		mixinStandardHelpOptions = true, version = Benchmarks.VERSION)
final class StackCommand implements Callable<Integer>, HarnessRules.JavaEngineOnly {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Option(names = "--only", paramLabel = "EXPERIMENT", description = "run one experiment of the stack")
	private @Nullable String only;

	@Option(names = "--archive",
			description = "first move each experiment's latest results into its next run-N/ (kept history)")
	private boolean archive;

	@Option(names = "--runs", defaultValue = "1",
			description = "independent runs of each experiment, each on a fresh stack, rotating through the "
					+ "experiments; with more than one, every run first archives the one before")
	private int runs;

	@Option(names = "--keep", description = "leave the stack running afterwards")
	private boolean keep;

	private final HarnessSetup setup;

	private final @Nullable String stack;

	private StackCommand(HarnessSetup setup, @Nullable String stack) {
		this.setup = setup;
		this.stack = stack;
	}

	/**
	 * A stack's command.
	 * @param setup what the command works with
	 * @param stack the stack
	 * @return the command
	 */
	static StackCommand of(HarnessSetup setup, String stack) {
		return new StackCommand(setup, stack);
	}

	/**
	 * {@code all}: every stack's.
	 * @param setup what the command works with
	 * @return the command
	 */
	static StackCommand all(HarnessSetup setup) {
		return new StackCommand(setup, null);
	}

	@Override
	public Integer call() {
		List<String> names = Experiments.standard().all().stream().map(Experiment::id).toList();
		if (this.runs < 1) {
			throw new ParameterException(this.spec.commandLine(), "--runs: at least 1, not " + this.runs);
		}
		if (this.only != null && !names.contains(this.only)) {
			throw new ParameterException(this.spec.commandLine(),
					"--only: no experiment '" + this.only + "' (one of " + String.join(", ", names) + ")");
		}
		Campaign campaign = campaign();
		Map<String, List<String>> plan = (this.stack != null) ? campaign.plan(List.of(this.stack), this.only)
				: campaign.planAll(this.only);
		campaign.run(plan, new Campaign.Options(this.archive, this.runs, this.keep));
		return 0;
	}

	private Campaign campaign() {
		RepositoryLayout layout = this.setup.layout();
		GitRepository git = new GitRepository(layout.root());
		Provenance provenance = new Provenance(git, MachineName.of(this.setup.environment()),
				EngineBuild.current().orElse(null));
		Simulations simulations = Simulations.onTheEngine();
		// one host machine for the predictions and the seal that checks it
		Optional<MachineFit> machine = this.setup.machine();
		CampaignTools tools = new CampaignTools(new ModelInputs(simulations, machine.orElse(null)),
				new Prediction(simulations, layout.kneeRange(), provenance::of, this.setup.log()), layout.k6Summaries(),
				this.setup.log());
		Seals seals = Seals.of(layout.engines(), git, machine.map(MachineFit::digest).orElse(null));
		// each check reads the provenance afresh, as run.py did:
		// a campaign runs for hours
		return new Campaign(this.setup.instruments(), tools, (experiment) -> seals.of(experiment, provenance.of()),
				new CampaignMachine(layout, this.setup.environment(), this.setup.processes(), this.setup.log()));
	}

}
