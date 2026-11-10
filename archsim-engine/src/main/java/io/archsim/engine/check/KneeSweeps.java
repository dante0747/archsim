package io.archsim.engine.check;

import java.util.List;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.sweep.BreakingPoint;
import io.archsim.engine.sweep.Sweep;
import io.archsim.engine.sweep.SweepRange;
import io.archsim.engine.sweep.SweepRequest;
import io.archsim.model.DesignDocuments;

/**
 * The breaking points of designs compared (a design and its baseline) on one load grid,
 * so a difference comes from the designs, not from two grids. The grid spans every
 * design's suggested range; when every design has a breaking point, a second sweep on a
 * finer shared grid, from the lower one to the first load above the higher one, narrows
 * them down.
 */
public final class KneeSweeps {

	/**
	 * The constant-load runs of each sweep, the Python engine's {@code KNEE_STEPS}, which
	 * also bounds the runs it made at once.
	 */
	public static final int STEPS = 6;

	private final BreakingPoint breakingPoint;

	private final ScenarioResolver resolver;

	/**
	 * Breaking points on one grid.
	 * @param breakingPoint what sweeps each design
	 * @param resolver what resolves a design or its scenario, for its load
	 */
	public KneeSweeps(BreakingPoint breakingPoint, ScenarioResolver resolver) {
		this.breakingPoint = breakingPoint;
		this.resolver = resolver;
	}

	/**
	 * Each design's breaking point on the shared grid.
	 * @param documents the designs, in order
	 * @param scenarioId the scenario of each to sweep; {@code null} for the designs as
	 * drawn
	 * @param limits the limits each step's run is held to
	 * @return each design's breaking point, in the designs' order
	 */
	public List<KneeFinding> of(List<JsonNode> documents, @Nullable String scenarioId, SimulationLimits limits) {
		List<SweepRange> ranges = documents.stream().map((document) -> around(document, scenarioId, limits)).toList();
		double gridFrom = ranges.stream().mapToDouble(SweepRange::fromRps).min().orElseThrow();
		double gridTo = ranges.stream().mapToDouble(SweepRange::toRps).max().orElseThrow();
		List<Sweep> coarse = sweep(documents, scenarioId, limits, new SweepRange(gridFrom, gridTo));
		List<Sweep> sweeps = narrowed(coarse, documents, scenarioId, limits);
		double resolution = gridStep(sweeps.getFirst().levels());
		return sweeps.stream()
			.map((sweep) -> new KneeFinding(sweep.maxWithinSloRps().orElse(null), sweep.analysis().verdict(),
					List.of(gridFrom, gridTo), resolution))
			.toList();
	}

	// the sweeps on a finer grid, from the lowest breaking point to the first load
	// above the highest, when every design has one on both grids; else the coarse ones
	private List<Sweep> narrowed(List<Sweep> coarse, List<JsonNode> documents, @Nullable String scenarioId,
			SimulationLimits limits) {
		List<Double> knees = coarse.stream().flatMap((sweep) -> sweep.maxWithinSloRps().stream()).toList();
		if (knees.size() < coarse.size()) {
			return coarse;
		}
		double lowestKnee = knees.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
		double highestKnee = knees.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
		double nextLoadAbove = coarse.getFirst()
			.levels()
			.stream()
			.filter((level) -> level > highestKnee)
			.findFirst()
			.orElse(highestKnee);
		if (nextLoadAbove <= lowestKnee) {
			return coarse;
		}
		// every step is seeded: a load both grids share gives the same result
		List<Sweep> fine = sweep(documents, scenarioId, limits, new SweepRange(lowestKnee, nextLoadAbove));
		return fine.stream().allMatch((sweep) -> sweep.maxWithinSloRps().isPresent()) ? fine : coarse;
	}

	// the range around a design's (or its scenario's) load, within what a step can run
	private SweepRange around(JsonNode document, @Nullable String scenarioId, SimulationLimits limits) {
		return SweepRange.around(
				this.resolver.resolve(DesignDocuments.load(document), scenarioId).document().workload(), limits,
				SweepRequest.DURATION_S);
	}

	private List<Sweep> sweep(List<JsonNode> documents, @Nullable String scenarioId, SimulationLimits limits,
			SweepRange range) {
		SweepRequest request = SweepRequest.of(scenarioId).withRange(range).withSteps(STEPS).withLimits(limits);
		return documents.stream().map((document) -> this.breakingPoint.sweep(document, request)).toList();
	}

	// the widest gap between the grid's loads
	private static double gridStep(List<Double> loads) {
		return IntStream.range(1, loads.size())
			.mapToDouble((index) -> loads.get(index) - loads.get(index - 1))
			.max()
			.orElse(0.0);
	}

}
