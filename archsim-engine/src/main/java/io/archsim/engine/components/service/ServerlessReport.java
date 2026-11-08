package io.archsim.engine.components.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.metrics.Counter;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.text.NumberText;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.text.PythonFormat;

/**
 * What a serverless function's report adds: its cold starts and instances, the series
 * behind them, the instances it needs by Little's law, and the behaviours of a function.
 */
final class ServerlessReport {

	/** How fast a function may add instances. */
	private static final TechnologyTrait SCALE_RATE = new TechnologyTrait("scale_rate", "Scale-out rate",
			"A function adds at most 1,000 concurrent instances every 10 seconds; requests beyond the instances it "
					+ "may start are throttled.",
			"https://docs.aws.amazon.com/lambda/latest/dg/scaling-behavior.html");

	/** How a function's cold start follows its runtime. */
	private static final TechnologyTrait RUNTIME_COLD_START = new TechnologyTrait("runtime_cold_start",
			"Cold start by runtime",
			"Cold starts range from under 100 ms to over a second. AWS publishes no per-runtime figures; the defaults "
					+ "here (JVM and .NET slower than Go, Rust, Node.js or Python) are illustrative, so measure "
					+ "your own.",
			"https://docs.aws.amazon.com/lambda/latest/dg/lambda-runtime-environment.html");

	/** A technology's behaviours that a function shows when it warms up. */
	private static final Set<String> WARM_UP_TRAITS = Set.of("jit_warmup", "tiered_compilation");

	private static final double MS_PER_SECOND = 1000.0;

	private static final int INSTANCES_DIGITS = 3;

	private ServerlessReport() {
	}

	/**
	 * The function's own summary figures: its cold starts over the run, and its instances
	 * on average over the window, at their peak and at the end.
	 * @param coldStarts the instances started over the run
	 * @param instances its instances
	 * @return the figures, in the report's order
	 */
	static SequencedMap<String, @Nullable Number> extraSummary(long coldStarts, FunctionInstances instances) {
		SequencedMap<String, @Nullable Number> figures = new LinkedHashMap<>();
		figures.put("coldStartsTotal", coldStarts);
		figures.put("replicasMean", PythonRound.round(instances.windowMean(), INSTANCES_DIGITS));
		figures.put("replicasPeak", instances.peak());
		figures.put("replicasEnd", instances.alive());
		return figures;
	}

	/**
	 * The function's own series: its instances, and its cold starts per second once it
	 * has started one in the window.
	 * @param metrics its metrics
	 * @param instances its instances
	 * @return the series, in the report's order
	 */
	static SequencedMap<String, List<@Nullable Double>> extraTimeseries(NodeMetrics metrics,
			FunctionInstances instances) {
		SequencedMap<String, List<@Nullable Double>> series = new LinkedHashMap<>();
		series.put("replicas", instances.countSeries());
		Counter coldStarts = metrics.counters().get(ServerlessRuntime.COLD_STARTS);
		if (coldStarts != null) {
			series.put("coldStartRps", RuntimeComponent.series(coldStarts.rateSeries()));
		}
		return series;
	}

	/**
	 * The behaviours of a function: its scale-out rate and its runtime's cold start, then
	 * its technology's warm-up when it has one.
	 * @param config the function's settings
	 * @return the traits
	 */
	static List<TechnologyTrait> behaviour(ServiceConfig config) {
		List<TechnologyTrait> traits = new ArrayList<>(List.of(SCALE_RATE, RUNTIME_COLD_START));
		if (config.warmup().isPresent()) {
			ServiceTraits.of(config)
				.stream()
				.filter((trait) -> WARM_UP_TRAITS.contains(trait.id()))
				.forEach(traits::add);
		}
		return traits;
	}

	/**
	 * The instances the function needs at the load it carried, by Little's law.
	 * @param config the function's settings
	 * @param summary its summary in the report
	 * @return the formula
	 */
	static Formula concurrency(ServiceConfig config, NodeSummary summary) {
		double arrivalRps = orZero(summary.arrivalRps());
		double holdMs = orZero(summary.meanHoldMs());
		return Formula
			.of("concurrency", "Instances needed",
					"concurrent instances = arrival rate × time per request (Little's law)",
					NumberText.formula(arrivalRps, 2) + " req/s × " + NumberText.formula(holdMs, 2) + " ms ÷ 1000")
			.withResult(arrivalRps * holdMs / MS_PER_SECOND, "instances")
			.withNote("Limit " + PythonFormat.format(config.concurrencyLimit(), ",") + "; new instances at most "
					+ PythonFormat.format(config.scaleOutRate(), ",") + " per 10 s, each paying a cold start of about "
					+ NumberText.formula(config.coldStartInEffect().expected(), 1) + " ms.")
			.withMetric("replicas");
	}

	private static double orZero(@Nullable Double value) {
		return (value != null) ? value : 0.0;
	}

}
