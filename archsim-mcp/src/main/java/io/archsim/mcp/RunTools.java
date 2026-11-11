package io.archsim.mcp;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.compare.Labels;
import io.archsim.engine.compare.RunComparer;
import io.archsim.engine.run.SimulationLimitException;
import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.SimulationMode;
import io.archsim.engine.simulator.Simulator;
import io.archsim.engine.slo.StoredObjectives;
import io.archsim.engine.sweep.BreakingPoint;
import io.archsim.engine.sweep.SweepRange;
import io.archsim.engine.sweep.SweepRequest;
import io.archsim.model.DesignDocuments;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * The tools that simulate: {@code simulate}, {@code get_results}, {@code compare} and
 * {@code breaking_point}, as the Python server answered them, each run held to the
 * server's limits and kept for the session.
 */
final class RunTools {

	private final McpSettings settings;

	private final KeptRuns runs = new KeptRuns();

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	/**
	 * The tools, held to the server's limits.
	 * @param settings the server's settings
	 */
	RunTools(McpSettings settings) {
		this.settings = settings;
	}

	/**
	 * {@code simulate}: a run of the design or one of its scenarios, summarized, and
	 * kept.
	 * @param arguments {@code document}, {@code scenario_id}, {@code mode}
	 * @return the run's summary
	 * @throws ToolRefusal when the document cannot be used or the run is over its limits
	 */
	JsonNode simulate(Arguments arguments) {
		SimulationMode mode = "sampled".equals(arguments.text("mode")) ? SimulationMode.SAMPLED : SimulationMode.FULL;
		Run run = run(arguments.document(), arguments.text("scenario_id"), mode);
		keep(run);
		return run.summary();
	}

	/**
	 * {@code get_results}: a kept run, summarized again or in full.
	 * @param arguments {@code run_id}, {@code detail}
	 * @return the summary, or the whole report
	 * @throws ToolRefusal when no run of the session has that id
	 */
	JsonNode results(Arguments arguments) {
		String id = arguments.required("run_id");
		JsonNode report = this.runs.report(id)
			.orElseThrow(() -> new ToolRefusal("no run '" + id + "' in this session (kept: " + this.runs.ids() + ")"));
		if ("full".equals(arguments.text("detail"))) {
			return report;
		}
		// as the run was stored: a target left out is off
		return RunSummaries.of(report, StoredObjectives.read(report.at("/input/objectives")));
	}

	/**
	 * {@code compare}: two scenarios of the design run, kept, and compared.
	 * @param arguments {@code document}, {@code a}, {@code b}
	 * @return both runs' summaries, the figures' changes, and why they changed
	 * @throws ToolRefusal when the document cannot be used or a run is over its limits
	 */
	JsonNode compare(Arguments arguments) {
		JsonNode document = arguments.document();
		String first = arguments.text("a");
		String second = arguments.text("b");
		Run firstRun = run(document, first, SimulationMode.FULL);
		Run secondRun = run(document, second, SimulationMode.FULL);
		keep(firstRun);
		keep(secondRun);
		JsonNode comparison = RecordCodec
			.write(RunComparer.compare(firstRun.report(), secondRun.report(), new Labels(label(first), label(second))));
		return JsonNodeFactory.instance.objectNode()
			.set("a", firstRun.summary())
			.set("b", secondRun.summary())
			.set("kpis", JsonPicks.value(comparison.path("kpis")))
			.set("explanations", JsonPicks.value(comparison.path("explanations")))
			.set("warnings", JsonPicks.value(comparison.path("warnings")));
	}

	/**
	 * {@code breaking_point}: the highest load the design holds within its objectives,
	 * from short constant-load runs.
	 * @param arguments {@code document}, {@code scenario_id}, {@code from_rps},
	 * {@code to_rps}, {@code steps}
	 * @return the sweep
	 * @throws ToolRefusal when the document cannot be used
	 */
	JsonNode breakingPoint(Arguments arguments) {
		int steps = arguments.integer("steps", SweepRequest.STEPS);
		SweepRequest request = SweepRequest.of(arguments.text("scenario_id"))
			.withSteps(steps)
			.withLimits(this.settings.limits());
		Double from = arguments.number("from_rps");
		Double to = arguments.number("to_rps");
		if (from != null && to != null) {
			request = request.withRange(new SweepRange(from, to));
		}
		ScenarioResolver resolver = new ScenarioResolver(StandardComponents.registry(), PriceSnapshots.shipped());
		int parallelism = Math.max(1, Math.min(steps, Runtime.getRuntime().availableProcessors()));
		try {
			return new BreakingPoint(this.simulator, resolver, parallelism).sweep(arguments.document(), request)
				.toJson();
		}
		catch (DocumentInvalidException ex) {
			throw ToolRefusal.unusable(ex);
		}
	}

	// a run's label in a comparison, as Python's `a or "base"` gave it: the design as
	// drawn when no scenario is named, or an empty one
	private static String label(@Nullable String scenario) {
		return (scenario == null || scenario.isEmpty()) ? "base" : scenario;
	}

	// _simulate: the run and its summary against the design's objectives
	private Run run(JsonNode document, @Nullable String scenario, SimulationMode mode) {
		JsonNode report;
		try {
			report = this.simulator.run(document,
					RunRequest.of(scenario).withMode(mode).withLimits(this.settings.limits()));
		}
		catch (DocumentInvalidException ex) {
			throw ToolRefusal.unusable(ex);
		}
		catch (SimulationLimitException ex) {
			throw new ToolRefusal("The run hit a limit: " + ex.getMessage() + ". Shorten durationS or lower the load.",
					ex);
		}
		return new Run(report, RunSummaries.of(report, DesignDocuments.load(document).objectives()));
	}

	private void keep(Run run) {
		this.runs.keep(run.summary().path("runId").stringValue(), run.report());
	}

	/**
	 * A run and its summary.
	 *
	 * @param report its report
	 * @param summary its summary
	 */
	private record Run(JsonNode report, ObjectNode summary) {

	}

}
