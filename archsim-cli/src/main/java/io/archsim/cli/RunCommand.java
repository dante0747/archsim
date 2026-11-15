package io.archsim.cli;

import java.io.PrintWriter;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.run.SimulationLimitException;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.SimulationMode;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * {@code archsim run}: simulates a design and prints the Python engine's CLI's summary,
 * how far to trust the run, its findings and its digests ({@link RunText}); refuses an
 * invalid design, or a run over its limits, on standard error with exit code 1.
 */
@Command(name = "run", description = "simulate an architecture document", mixinStandardHelpOptions = true,
		versionProvider = VersionProvider.class)
final class RunCommand implements Callable<Integer> {

	@Spec
	private CommandSpec spec = CommandSpec.create();

	@Parameters(index = "0", paramLabel = "file", description = "the design to simulate")
	private @Nullable Path file;

	@Option(names = "--scenario", description = "scenario id to apply")
	private @Nullable String scenario;

	@Option(names = "--seed", converter = PythonInt.class, description = "override the workload seed")
	private @Nullable BigInteger seed;

	@Option(names = "--out", description = "write the full JSON report to this file")
	private @Nullable String out;

	@Option(names = "--sampled", description = "for a long steady load: simulate a steady sample and answer the run "
			+ "from it (docs/performance.md); falls back to a full run when the load is not steady")
	private boolean sampled;

	@Option(names = "--max-requests", description = "the most requests a run may make (default: ${DEFAULT-VALUE})")
	private long maxRequests = SimulationLimits.DEFAULT.maxRequests();

	@Option(names = "--max-wall-seconds",
			description = "the longest a run may take, in seconds (default: ${DEFAULT-VALUE})")
	private double maxWallSeconds = SimulationLimits.DEFAULT.maxWallSeconds();

	/**
	 * Simulates the design and prints the run.
	 * @return 0 for a run, 1 for a design refused
	 * @throws CommandException when the design cannot be read or the report written
	 * @throws ParameterException when a limit is not a number above zero
	 */
	@Override
	public Integer call() {
		PrintWriter stdout = this.spec.commandLine().getOut();
		PrintWriter stderr = this.spec.commandLine().getErr();
		RunRequest request = request();
		JsonNode document = readDesign();
		ObjectNode report;
		try {
			report = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped()).run(document, request);
		}
		catch (DocumentInvalidException ex) {
			stderr.println("Cannot simulate: document is invalid");
			IssueLines.of(ex.issues()).forEach(stdout::println);
			return 1;
		}
		catch (SimulationLimitException ex) {
			stderr.println("Cannot simulate: " + ex.getMessage());
			return 1;
		}
		RunText.lines(report).forEach(stdout::println);
		String reportFile = PythonOptions.given(this.out);
		if (reportFile != null) {
			CommandFiles.write(reportFile, JsonOutput.text(report));
			stdout.println("report written to " + reportFile);
		}
		return 0;
	}

	// the seed goes into the workload before any scenario applies, as the Python CLI
	// did; a workload that is not an object is left for validation to refuse
	private JsonNode readDesign() {
		JsonNode document = CommandFiles.readJson(Objects.requireNonNull(this.file));
		if (this.seed != null && document instanceof ObjectNode design) {
			JsonNode workload = design.get("workload");
			if (workload == null) {
				design.putObject("workload").put("seed", this.seed);
			}
			else if (workload instanceof ObjectNode settings) {
				settings.put("seed", this.seed);
			}
		}
		return document;
	}

	private RunRequest request() {
		if (this.maxRequests <= 0) {
			throw new ParameterException(this.spec.commandLine(), "--max-requests must be above 0");
		}
		if (!(this.maxWallSeconds > 0) || Double.isInfinite(this.maxWallSeconds)) {
			throw new ParameterException(this.spec.commandLine(), "--max-wall-seconds must be a number above 0");
		}
		RunRequest request = RunRequest.of(this.scenario)
			.withLimits(new SimulationLimits(this.maxRequests, this.maxWallSeconds));
		return this.sampled ? request.withMode(SimulationMode.SAMPLED) : request;
	}

}
