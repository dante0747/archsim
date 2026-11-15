package io.archsim.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.compare.Labels;
import io.archsim.engine.compare.RunComparer;
import io.archsim.engine.compare.RunComparison;
import io.archsim.engine.run.SimulationLimitException;
import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * {@code archsim compare}: simulates two scenarios of a design (or the design itself) and
 * prints each run's summary line, what changed in words and why the runs may differ other
 * than by their designs; a design refused, or a run over its limits, on standard error
 * with exit code 1.
 */
@Command(name = "compare", description = "simulate two scenarios and compare them", mixinStandardHelpOptions = true,
		versionProvider = VersionProvider.class)
final class CompareCommand implements Callable<Integer> {

	/** What a run of the design as drawn is called. */
	private static final String BASE = "base";

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Parameters(index = "0", paramLabel = "file", description = "the design to simulate")
	private @Nullable Path file;

	@Option(names = "--a", description = "scenario id for A (default: base document)")
	private @Nullable String first;

	@Option(names = "--b", description = "scenario id for B (default: base document)")
	private @Nullable String second;

	/**
	 * Simulates both runs and prints the comparison.
	 * @return 0 for a comparison, 1 for a design refused
	 * @throws CommandException when the design cannot be read
	 */
	@Override
	public Integer call() {
		PrintWriter stdout = this.spec.commandLine().getOut();
		JsonNode design = CommandFiles.readJson(Objects.requireNonNull(this.file));
		Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());
		ObjectNode firstRun;
		ObjectNode secondRun;
		try {
			firstRun = simulator.run(design, RunRequest.of(this.first));
			secondRun = simulator.run(design, RunRequest.of(this.second));
		}
		catch (DocumentInvalidException ex) {
			this.spec.commandLine().getErr().println("Cannot compare: document is invalid");
			IssueLines.of(ex.issues()).forEach(stdout::println);
			return 1;
		}
		catch (SimulationLimitException ex) {
			this.spec.commandLine().getErr().println("Cannot compare: " + ex.getMessage());
			return 1;
		}
		Labels labels = new Labels(label(this.first), label(this.second));
		RunComparison comparison = RunComparer.compare(firstRun, secondRun, labels);
		stdout.println("A (%s): %s".formatted(labels.a(), RunText.summaryLine(firstRun)));
		stdout.println("B (%s): %s".formatted(labels.b(), RunText.summaryLine(secondRun)));
		comparison.explanations().forEach((line) -> stdout.println("  - " + line));
		comparison.warnings().forEach((line) -> stdout.println("  ! " + line));
		return 0;
	}

	// a scenario by its id, the design as drawn as "base", an empty id counting as none
	private static String label(@Nullable String scenario) {
		return (scenario == null || scenario.isEmpty()) ? BASE : scenario;
	}

}
