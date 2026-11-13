package io.archsim.benchmarks.campaign;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.HarnessStopException;
import io.archsim.benchmarks.docker.CpuTopology;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.StackLifecycle;
import io.archsim.benchmarks.docker.Stacks;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;

/**
 * A campaign on Docker: each stack started afresh, each of its experiments calibrated,
 * predicted, measured and compared on it, the stack stopped. Several runs rotate through
 * the experiments (run 1 of each, then run 2), so that slow drift on the machine spreads
 * over all of them.
 */
public final class Campaign {

	private final Instruments instruments;

	private final StepsOnStack steps;

	private final Function<Experiment, Optional<String>> seal;

	private final CampaignMachine machine;

	/**
	 * A campaign on Docker and the engine in process.
	 * @param instruments what drives and reads the stacks
	 * @param tools the engine's simulations, the prediction and k6's folder
	 * @param seal why an experiment may not run now ({@link Seals}); empty when it may
	 * @param machine the repository, the environment, what runs commands and the log
	 */
	public Campaign(Instruments instruments, CampaignTools tools, Function<Experiment, Optional<String>> seal,
			CampaignMachine machine) {
		this(instruments, (running, serviceCpus) -> new DockerSteps(running, serviceCpus, tools), seal, machine);
	}

	/**
	 * A campaign whose experiments run on steps of their own.
	 * @param instruments what drives and reads the stacks
	 * @param steps an experiment's steps on a running stack
	 * @param seal why an experiment may not run now; empty when it may
	 * @param machine the repository, the environment, what runs commands and the log
	 */
	Campaign(Instruments instruments, StepsOnStack steps, Function<Experiment, Optional<String>> seal,
			CampaignMachine machine) {
		this.instruments = instruments;
		this.steps = steps;
		this.seal = seal;
		this.machine = machine;
	}

	/**
	 * The experiments to run on the stacks asked for by name, before any stack starts.
	 * @param stacks the stacks
	 * @param only the one experiment asked for, or null for all
	 * @return each stack with something to run, and its experiments
	 * @throws HarnessStopException when one of them is sealed
	 */
	public Map<String, List<String>> plan(List<String> stacks, @Nullable String only) {
		return planned(stacks, only, (experiment, why) -> {
			throw new HarnessStopException(why);
		});
	}

	/**
	 * The experiments to run on every stack, before any stack starts: a sealed one is
	 * left out, saying why.
	 * @param only the one experiment asked for, or null for all
	 * @return each stack with something to run, and its experiments
	 */
	public Map<String, List<String>> planAll(@Nullable String only) {
		return planned(new ArrayList<>(Experiments.standard().stacks().keySet()), only,
				(experiment, why) -> this.machine.log().line("leaving out " + experiment + ": " + why));
	}

	/**
	 * Runs a plan.
	 * @param plan each stack and its experiments
	 * @param options whether to keep each experiment's previous results, how many runs,
	 * and whether to leave the last stack running
	 */
	public void run(Map<String, List<String>> plan, Options options) {
		ObjectNode environment = new MachineEnvironment(this.machine.processes(), this.machine.environment()).of();
		Map<Integer, List<Integer>> siblings = CpuTopology.siblings(this.instruments.compose()).orElse(null);
		new CampaignRun(options, environment, siblings).run(plan);
	}

	private Map<String, List<String>> planned(List<String> stacks, @Nullable String only, Sealed sealed) {
		Map<String, List<String>> plan = new LinkedHashMap<>();
		for (String stack : stacks) {
			for (String experiment : Experiments.standard().stacks().getOrDefault(stack, List.of())) {
				if (only != null && !only.equals(experiment)) {
					continue;
				}
				Optional<String> why = this.seal.apply(Experiments.standard().get(experiment));
				if (why.isPresent()) {
					sealed.found(experiment, why.get());
					continue;
				}
				plan.computeIfAbsent(stack, (key) -> new ArrayList<>()).add(experiment);
			}
		}
		return plan;
	}

	/**
	 * How a campaign runs.
	 *
	 * @param archive whether to move each experiment's previous results into its next
	 * {@code run-N/} first
	 * @param runs independent runs of each experiment, each on a fresh stack, rotating
	 * through the experiments; with more than one, every run archives the one before
	 * @param keep whether to leave the stack running after the last run
	 */
	public record Options(boolean archive, int runs, boolean keep) {

		/**
		 * Checks the runs.
		 * @param archive whether to keep each experiment's previous results first
		 * @param runs independent runs of each experiment, at least one
		 * @param keep whether to leave the stack running after the last run
		 */
		public Options {
			if (runs < 1) {
				throw new IllegalArgumentException("a campaign runs each experiment at least once, not " + runs);
			}
		}

	}

	/**
	 * An experiment's steps on a running stack.
	 */
	@FunctionalInterface
	interface StepsOnStack {

		/**
		 * The steps.
		 * @param running what drives and reads the running stack
		 * @param serviceCpus the CPUs of its service under test
		 * @return the steps
		 */
		ExperimentSteps on(Instruments running, int serviceCpus);

	}

	/**
	 * What the campaign does with a sealed experiment it finds while planning.
	 */
	@FunctionalInterface
	private interface Sealed {

		void found(String experiment, String why);

	}

	/**
	 * One campaign's runs: the stacks it starts, and what every experiment's
	 * {@code environment.json} starts from.
	 */
	private final class CampaignRun {

		private final Options options;

		private final StackLifecycle stacks;

		private final Cpusets cpusets;

		private final ObjectNode environment;

		private final @Nullable Map<Integer, List<Integer>> siblings;

		/**
		 * The runs of a campaign on a machine.
		 * @param options the campaign's options
		 * @param environment the machine's {@code environment.json}, before any
		 * experiment's part of it
		 * @param siblings each CPU's physical core, when Docker could say
		 */
		CampaignRun(Options options, ObjectNode environment, @Nullable Map<Integer, List<Integer>> siblings) {
			this.options = options;
			CampaignMachine on = Campaign.this.machine;
			this.cpusets = Cpusets.declaredIn(on.layout().compose());
			this.stacks = new StackLifecycle(Campaign.this.instruments, this.cpusets, on.environment(), on.log());
			this.environment = environment;
			this.siblings = siblings;
		}

		void run(Map<String, List<String>> plan) {
			for (int run = 1; run <= this.options.runs(); run++) {
				for (Map.Entry<String, List<String>> stack : plan.entrySet()) {
					runStack(stack.getKey(), stack.getValue(), run);
				}
			}
		}

		// a stack started, its experiments run on it, the stack stopped unless kept
		private void runStack(String stack, List<String> experiments, int run) {
			Instruments running = this.stacks.up(stack);
			try {
				experiments.forEach((experiment) -> runExperiment(running, experiment, run));
			}
			finally {
				if (!this.options.keep() || run < this.options.runs()) {
					this.stacks.down(stack);
				}
			}
		}

		private void runExperiment(Instruments running, String name, int run) {
			HarnessLog log = Campaign.this.machine.log();
			if (this.options.archive() || this.options.runs() > 1) {
				Archive.keep(Campaign.this.machine.layout().results(), name)
					.ifPresent((kept) -> log
						.line(name + ": kept the previous results in " + Campaign.this.machine.layout().name(kept)));
			}
			if (this.options.runs() > 1) {
				log.line(name + ": run " + run + " of " + this.options.runs());
			}
			Experiment experiment = Experiments.standard().get(name);
			Map<String, String> sets = this.cpusets.of(experiment.stack(), Campaign.this.machine.environment());
			ResultFiles.write(Campaign.this.machine.layout().results().resolve(name).resolve("environment.json"),
					recorded(experiment, sets));
			String service = Stacks.standard().containers(experiment.stack()).getFirst();
			int serviceCpus = Cpusets.count(Objects.requireNonNull(sets.get(service), service));
			new ExperimentRun(Campaign.this.steps.on(running, serviceCpus), Campaign.this.machine.layout().results(),
					Campaign.this.seal, log)
				.run(experiment);
		}

		// the machine's environment with the experiment's image, its cpusets and how they
		// fall on physical cores, when Docker could say
		private ObjectNode recorded(Experiment experiment, Map<String, String> sets) {
			ObjectNode recorded = this.environment.deepCopy();
			((ObjectNode) recorded.get("images")).put("service", MachineEnvironment.image(experiment));
			ObjectNode pinned = recorded.putObject("cpusets");
			sets.forEach(pinned::put);
			if (this.siblings != null) {
				recorded.set("topology", CpuTopology.of(sets, this.siblings));
			}
			return recorded;
		}

	}

}
