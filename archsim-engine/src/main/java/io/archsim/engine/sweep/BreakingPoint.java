package io.archsim.engine.sweep;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.report.RunReport;
import io.archsim.engine.scenario.ResolvedDesign;
import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.DesignDocuments;
import io.archsim.model.document.ArchitectureDocument;

/**
 * A design's breaking point: the design (or a scenario of it), resolved and sized at its
 * own load, simulated at evenly spaced constant loads without its faults, and the curve
 * read. Every step runs with the design's seed, so the result is the same however many
 * steps run at once.
 */
public final class BreakingPoint {

	private final Simulator simulator;

	private final ScenarioResolver resolver;

	private final int parallelism;

	/**
	 * A breaking-point finder.
	 * @param simulator the simulator each step runs on
	 * @param resolver what resolves the design or its scenario
	 * @param parallelism how many steps run at once
	 */
	public BreakingPoint(Simulator simulator, ScenarioResolver resolver, int parallelism) {
		this.simulator = simulator;
		this.resolver = resolver;
		this.parallelism = parallelism;
	}

	/**
	 * Sweeps a design's load: the range asked for (or the one around the design's load),
	 * cut to what a step can run, its levels each run as a step.
	 * @param document the design
	 * @param request the scenario, range, steps, their length and limits
	 * @return the sweep
	 * @throws io.archsim.model.issue.DocumentInvalidException when the design is invalid,
	 * has no such scenario, or a step is not a valid run
	 * @throws io.archsim.engine.run.SimulationLimitException when a step is over a limit
	 */
	public Sweep sweep(JsonNode document, SweepRequest request) {
		ResolvedDesign base = this.resolver.resolve(DesignDocuments.load(document), request.scenarioId());
		ArchitectureDocument resolved = base.document();
		double cap = SweepRange.rpsCap(request.limits(), request.stepSeconds());
		SweepRange asked = request.range();
		SweepRange range = (asked != null)
				? new SweepRange(Math.min(asked.fromRps(), cap), Math.min(asked.toRps(), cap))
				: SweepRange.suggested(resolved.workload().rps(), cap);
		// rounding may lift the top step past a cap that is not a round number, and
		// a cap below the lowest load a step runs puts every step on it
		List<Double> loads = range.levels(request.steps())
			.stream()
			.map((level) -> Math.min(level, cap))
			.distinct()
			.toList();
		ObjectNode resolvedJson = DesignDocuments.toJson(resolved);
		List<ObjectNode> steps = loads.stream()
			.map((rps) -> step(resolvedJson, rps, request.stepSeconds(), request.warmupSeconds()))
			.toList();
		List<ObjectNode> reports = simulate(steps, RunRequest.of(null).withLimits(request.limits()));
		List<SweepRow> rows = IntStream.range(0, loads.size())
			.mapToObj((index) -> SweepRow.of(loads.get(index), RunReport.read(reports.get(index))))
			.toList();
		return new Sweep(SweepAnalysis.of(rows, resolved.objectives()), resolved.objectives(), range, loads);
	}

	// the resolved design at one constant load, without its faults
	private static ObjectNode step(ObjectNode resolvedJson, double rps, double durationS, double warmupS) {
		ObjectNode step = resolvedJson.deepCopy();
		ObjectNode workload = (ObjectNode) step.get("workload");
		workload.put("rps", rps);
		workload.put("durationS", durationS);
		workload.put("warmupS", warmupS);
		workload.putObject("pattern").put("kind", "constant");
		workload.putArray("faults");
		return step;
	}

	private List<ObjectNode> simulate(List<ObjectNode> steps, RunRequest run) {
		if (this.parallelism <= 1 || steps.size() <= 1) {
			return steps.stream().map((step) -> this.simulator.run(step, run)).toList();
		}
		try (ExecutorService pool = Executors.newFixedThreadPool(Math.min(this.parallelism, steps.size()))) {
			List<Future<ObjectNode>> runs = steps.stream()
				.map((step) -> pool.submit(() -> this.simulator.run(step, run)))
				.toList();
			return runs.stream().map(BreakingPoint::report).toList();
		}
	}

	private static ObjectNode report(Future<ObjectNode> run) {
		try {
			return run.get();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("a sweep was interrupted", ex);
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof RuntimeException failure) {
				throw failure;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException("a sweep's step failed", cause);
		}
	}

}
