package io.archsim.engine.components.kubernetes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.SequencedMap;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.metrics.AmountGauge;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.text.NumberText;
import io.archsim.model.catalog.ComponentCatalog;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.component.kubernetes.ClusterConfig;
import io.archsim.model.component.kubernetes.KubernetesClusterSpec;

/**
 * What a cluster's report adds: its nodes, pods and their waits, the share of its nodes'
 * CPU and memory its pods request, the series behind them, the behaviours of Kubernetes,
 * and the formulas for a node's allocatable resources and the time until a Pending pod
 * helps.
 */
final class ClusterReport {

	/** What each distribution brings, read once from the component catalog. */
	private static final Map<String, List<TechnologyTrait>> TRAITS = ComponentCatalog
		.technologyTraits(KubernetesClusterSpec.TYPE);

	/** The behaviour shown only when the cluster autoscaler is on. */
	private static final String AUTOSCALER_TRAIT = "cluster_autoscaler";

	private static final String RESERVED_RESOURCES = "https://kubernetes.io/docs/tasks/administer-cluster/reserve-compute-resources/";

	private static final String AUTOSCALER_FAQ = "https://github.com/kubernetes/autoscaler/blob/master/cluster-autoscaler/FAQ.md";

	private static final double MS_PER_SECOND = 1000.0;

	/** The decimals a figure of the summary is rounded to. */
	private static final int FIGURE_DIGITS = 3;

	/** The decimals a share of the nodes' resources requested is rounded to. */
	private static final int RATIO_DIGITS = 4;

	/** The decimals a formula's finer figures are written to. */
	private static final int FORMULA_DIGITS = 2;

	private ClusterReport() {
	}

	/**
	 * The cluster's own summary figures, in the report's order.
	 * @param pool its nodes and pods
	 * @return the figures
	 */
	static SequencedMap<String, @Nullable Number> extraSummary(NodePool pool) {
		ClusterGauges gauges = pool.gauges();
		SequencedMap<String, @Nullable Number> figures = new LinkedHashMap<>();
		figures.put("nodesMean", PythonRound.round(gauges.nodes().windowMean(), FIGURE_DIGITS));
		figures.put("nodesPeak", gauges.nodes().runPeak());
		figures.put("nodesEnd", pool.nodeCount());
		figures.put("podsPeak", gauges.running().runPeak());
		figures.put("podsPendingPeak", gauges.pending().runPeak());
		figures.put("podsPendingEnd", pool.pendingCount());
		OptionalDouble meanWaitMs = pool.meanWaitMs();
		figures.put("meanPendingS", meanWaitMs.isPresent()
				? PythonRound.round(meanWaitMs.getAsDouble() / MS_PER_SECOND, FIGURE_DIGITS) : null);
		figures.put("maxPendingS", PythonRound.round(pool.maxWaitMs() / MS_PER_SECOND, FIGURE_DIGITS));
		figures.put("cpuRequestedRatio", requestedRatio(gauges.cpuRequested(), gauges.cpuAllocatable()));
		figures.put("memoryRequestedRatio", requestedRatio(gauges.memoryRequested(), gauges.memoryAllocatable()));
		figures.put("allocatableVcpuPerNode", PythonRound.round(pool.nodeCpu(), FIGURE_DIGITS));
		figures.put("allocatableGibPerNode", PythonRound.round(pool.nodeMemoryGib(), FIGURE_DIGITS));
		return figures;
	}

	/**
	 * The cluster's own series: its nodes, pods running and Pending, nodes being
	 * provisioned, and the share of its nodes' CPU and memory its pods request.
	 * @param gauges its gauges
	 * @return the series, in the report's order
	 */
	static SequencedMap<String, List<@Nullable Double>> extraTimeseries(ClusterGauges gauges) {
		SequencedMap<String, List<@Nullable Double>> series = new LinkedHashMap<>();
		series.put("nodes", gauges.nodes().meanSeriesUntilEnd());
		series.put("podsRunning", gauges.running().meanSeriesUntilEnd());
		series.put("podsPending", gauges.pending().meanSeriesUntilEnd());
		series.put("nodesProvisioning", gauges.provisioning().meanSeriesUntilEnd());
		series.put("cpuRequestedRatio", ratios(gauges.cpuRequested(), gauges.cpuAllocatable()));
		series.put("memoryRequestedRatio", ratios(gauges.memoryRequested(), gauges.memoryAllocatable()));
		return series;
	}

	/**
	 * The behaviours of Kubernetes in effect: its cluster autoscaler only when it is on.
	 * @param config the cluster's settings
	 * @return the traits
	 */
	static List<TechnologyTrait> behaviour(ClusterConfig config) {
		return TRAITS.getOrDefault(config.technology().json(), List.of())
			.stream()
			.filter((trait) -> config.clusterAutoscaler() || !AUTOSCALER_TRAIT.equals(trait.id()))
			.toList();
	}

	/**
	 * The resources a node offers pods, and the time until a Pending pod helps.
	 * @param config the cluster's settings
	 * @param summary its summary in the report
	 * @return the formulas
	 */
	static List<Formula> explain(ClusterConfig config, NodeSummary summary) {
		List<Formula> formulas = new ArrayList<>();
		formulas.add(Formula
			.of("allocatable", "Allocatable per node", "allocatable = node size × allocatable share",
					num(config.nodeVcpu(), 1) + " vCPU and " + num(config.nodeMemoryGib(), 1) + " GiB × "
							+ num(config.allocatableShare(), FORMULA_DIGITS))
			.withResult(config.allocatableCpu(), "vCPU")
			.withNote("and " + num(config.allocatableMemoryGib(), FORMULA_DIGITS)
					+ " GiB of memory for pods on each node.")
			.withSource(RESERVED_RESOURCES)
			.withMetric("cpuRequestedRatio"));
		double delayS = config.nodeProvisionS() + config.imagePullS();
		formulas.add(Formula
			.of("time_to_capacity", "Time until a Pending pod helps",
					"wait ≈ scan interval + node provisioning + image pull + pod start-up",
					"up to " + num(config.scanIntervalS(), 1) + " + " + num(config.nodeProvisionS(), 1) + " + "
							+ num(config.imagePullS(), 1) + " s + the service's start-up")
			.withResult(delayS + config.scanIntervalS() / 2, "s")
			.withNote("Measured: pods waited " + num(summary.meanPendingS(), FORMULA_DIGITS)
					+ " s on average for a node (longest " + num(summary.maxPendingS(), FORMULA_DIGITS) + " s).")
			.withSource(AUTOSCALER_FAQ)
			.withMetric("podsPending"));
		return formulas;
	}

	// requested over allocatable on average over the window; a cluster always has a node
	private static double requestedRatio(AmountGauge requested, AmountGauge allocatable) {
		return PythonRound.round(requested.windowMean() / allocatable.windowMean(), RATIO_DIGITS);
	}

	// requested over allocatable in each bucket; none for a bucket without any
	// allocatable
	private static List<@Nullable Double> ratios(AmountGauge requested, AmountGauge allocatable) {
		double[] used = requested.area();
		double[] offered = allocatable.area();
		List<@Nullable Double> ratios = new ArrayList<>(used.length);
		for (int bucket = 0; bucket < used.length; bucket++) {
			ratios.add((offered[bucket] != 0) ? used[bucket] / offered[bucket] : null);
		}
		return ratios;
	}

	private static String num(@Nullable Double value, int digits) {
		return NumberText.formula(value, digits);
	}

}
