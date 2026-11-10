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

import io.archsim.engine.analysis.DesignAnalyzer;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * {@code archsim analyze}: a design's auto sizes, cost estimate, design review and
 * technology tiers, without simulating, as JSON; a design that cannot be read or resolved
 * refused on standard error with its issues, exit code 1.
 */
@Command(name = "analyze",
		description = "print the auto sizes, cost estimate, design review and technology tiers as JSON",
		mixinStandardHelpOptions = true, version = Archsim.VERSION)
final class AnalyzeCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Parameters(index = "0", paramLabel = "file", description = "the design to analyze")
	private @Nullable Path file;

	@Option(names = "--scenario", description = "scenario id to apply")
	private @Nullable String scenario;

	/**
	 * Analyzes the design and prints the analysis.
	 * @return 0 for an analysis, 1 for a design refused
	 * @throws CommandException when the design cannot be read
	 */
	@Override
	public Integer call() {
		PrintWriter stdout = this.spec.commandLine().getOut();
		JsonNode design = CommandFiles.readJson(Objects.requireNonNull(this.file));
		JsonNode analysis;
		try {
			analysis = new DesignAnalyzer(StandardComponents.registry(), PriceSnapshots.shipped())
				.analyze(design, this.scenario)
				.toJson();
		}
		catch (DocumentInvalidException ex) {
			this.spec.commandLine().getErr().println("Cannot analyze: document is invalid");
			IssueLines.of(ex.issues()).forEach(stdout::println);
			return 1;
		}
		JsonOutput.print(stdout, analysis);
		return 0;
	}

}
