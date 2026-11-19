package io.archsim.benchmarks.designs;

import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.analysis.DesignAnalyzer;
import io.archsim.engine.estimate.InstantEstimate;
import io.archsim.engine.estimate.Latency;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * The engine's instant estimates of the benchmark designs, in this process: what its
 * queueing formulas say of a design at one load without simulating it, as the app and the
 * command line show it, and the knee they give.
 */
public final class Estimates {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final DesignAnalyzer analyzer;

	/**
	 * Estimates by an engine's analysis.
	 * @param analyzer the engine's analysis of a design
	 */
	public Estimates(DesignAnalyzer analyzer) {
		this.analyzer = analyzer;
	}

	/**
	 * Estimates by the engine with its standard components and the prices it ships.
	 * @return the estimates
	 */
	public static Estimates onTheEngine() {
		return new Estimates(new DesignAnalyzer(StandardComponents.registry(), PriceSnapshots.shipped()));
	}

	/**
	 * A design's instant estimate, as the harness reads it.
	 * @param design the design
	 * @return its offered load; its approximate {@code p95}, None when the estimate gives
	 * no latency (a part a request waits for is overloaded); whether a part is
	 * {@code overloaded}; the load the design saturates at; the estimate's own summary;
	 * and a {@code successRate} that is not the estimate's, which has none, but what the
	 * knee search's objective reads: 1 with a latency, for the estimate fails no request,
	 * and None without one. The baselines record the p95 alone
	 */
	public ObjectNode estimate(ObjectNode design) {
		InstantEstimate estimate = this.analyzer.analyze(design, null).estimate();
		Latency latency = estimate.latencyMs();
		Double p95 = (latency != null) ? latency.p95() : null;
		ObjectNode read = NODES.objectNode().set("offeredRps", design.at("/workload/rps"));
		read.put("p95", p95);
		read.put("successRate", (p95 != null) ? 1.0 : null);
		read.put("overloaded", estimate.overloaded());
		read.put("saturationRps", estimate.saturationRps());
		return read.put("summary", estimate.summary());
	}

	/**
	 * The highest load whose estimate meets the objective, searched as a simulated knee
	 * is: by doubling from 100 req/s, then bisection to 1%.
	 * @param estimate a design's estimate at an offered load ({@link #estimate})
	 * @return {@code kneeRps} and the loads {@code tried}
	 */
	public static ObjectNode knee(Function<JsonNode, ObjectNode> estimate) {
		return KneeSearch.knee(estimate, PythonValues.none());
	}

}
