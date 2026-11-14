package io.archsim.engine.cost;

import java.util.Map;
import java.util.function.Function;

import io.archsim.engine.estimate.InstantEstimate;
import io.archsim.engine.load.LoadEstimator;
import io.archsim.engine.report.RunReport;
import io.archsim.model.catalog.ComponentFacts;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.ProvenanceKind;
import io.archsim.model.document.Workload;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * A design's monthly cost, from each part's price per instance-hour ({@link Quotes}),
 * managed and per-request parts at a price snapshot's rates ({@link ManagedLines}),
 * third-party APIs per call ({@link ExternalApiLine}), and services on Kubernetes
 * ({@link KubernetesLines}). A month bills the workload's peak hours at its peak and the
 * rest at its steady base load ({@link MonthlyCost}, ADR-0074): before a run, from the
 * static load estimate at each rate; after one, from the rates the run measured at each
 * ({@link MeasuredUsage}), with the cost per million successful requests. An autoscaled
 * service or cluster is billed at each load the count it settles at there
 * ({@link SettledCounts}). These are planning estimates, not quotes.
 */
public final class CostEstimator {

	/** The hours a month is billed for. */
	public static final int HOURS_PER_MONTH = 730;

	/**
	 * The share of an implied Kubernetes node's CPU and memory left for pods (the
	 * cluster's default).
	 */
	public static final double KUBERNETES_ALLOCATABLE = 0.9;

	/** SQS bills each API call: a message is sent, received and deleted. */
	public static final int SQS_REQUESTS_PER_MESSAGE = 3;

	/** Illustrative: what a load balancer processes per request, in KB. */
	public static final double ALB_KB_PER_REQUEST = 10.0;

	/** A load balancer's capacity unit covers this many GB processed an hour. */
	public static final double ALB_GB_PER_LCU_HOUR = 1.0;

	/** What a snapshot's prices leave out. */
	public static final String NOT_MODELLED = "Not modelled: Multi-AZ deployments, reserved instances and savings "
			+ "plans, data transfer and NAT, and free tiers.";

	/** The seconds in an hour. */
	public static final double SECONDS_PER_HOUR = 3600.0;

	/** The seconds a month's per-request costs are counted over. */
	public static final double SECONDS_PER_MONTH = HOURS_PER_MONTH * SECONDS_PER_HOUR;

	private final LevelPricing.Catalog catalog;

	private final LoadEstimator loads;

	/**
	 * Prices designs made of a registry's types.
	 * @param registry the component types
	 * @param prices the price snapshots on this machine
	 */
	public CostEstimator(ComponentRegistry registry, PriceSnapshots prices) {
		this.catalog = new LevelPricing.Catalog(registry, new ComponentFacts(registry), prices);
		this.loads = new LoadEstimator(registry);
	}

	/**
	 * The monthly cost of a design before a run.
	 * @param document the design as simulated: resolved and auto-sized
	 * @param provenance the document's provenance kind by path: a price marked measured
	 * or assumed is the user's, whatever its value
	 * @param busy the CPU cores one instance of each part keeps busy at the base load and
	 * at the peak, so a burstable type above its baseline is charged its surplus CPU
	 * credits
	 * @param estimate the design's instant estimate, for a function's duration and the
	 * replicas an autoscaled service keeps busy
	 * @return the cost
	 */
	public CostEstimate estimate(ArchitectureDocument document, Map<String, ProvenanceKind> provenance, BusyCores busy,
			InstantEstimate estimate) {
		return cost(document, new LevelPricing.Inputs(provenance, busy, billedPeakHours(document)),
				(level) -> EstimatedUsage.of(document, this.loads, estimate, level));
	}

	/**
	 * The monthly cost of a design after a run of it.
	 * @param document the design the run simulated: resolved and auto-sized
	 * @param provenance the document's provenance kind by path: a price marked measured
	 * or assumed is the user's, whatever its value
	 * @param busy the CPU cores one instance of each part keeps busy at the base load and
	 * at the peak, so a burstable type above its baseline is charged its surplus CPU
	 * credits
	 * @param report the run's report
	 * @return the cost
	 * @throws IllegalArgumentException when the report lists no parts
	 */
	public CostEstimate estimateAfterRun(ArchitectureDocument document, Map<String, ProvenanceKind> provenance,
			BusyCores busy, RunReport report) {
		return cost(document, new LevelPricing.Inputs(provenance, busy, billedPeakHours(document)),
				(level) -> MeasuredUsage.of(document, this.loads, report, level));
	}

	/**
	 * A design priced at its base load and at its peak, unless the pattern's peak is its
	 * base, for a month of its peak hours at the peak and the rest at the base load.
	 * @param document the design as simulated
	 * @param inputs the provenance of its values and the CPU its parts keep busy
	 * @param loads what each part handles at a load
	 * @return the cost
	 */
	private CostEstimate cost(ArchitectureDocument document, LevelPricing.Inputs inputs,
			Function<LoadLevel, LevelLoad> loads) {
		PricedLoad base = LevelPricing.priced(this.catalog, document, inputs, loads.apply(LoadLevel.BASE));
		if (!hasPeak(document.workload())) {
			return MonthlyCost.of(base, base, inputs.peakHours());
		}
		PricedLoad peak = LevelPricing.priced(this.catalog, document, inputs, loads.apply(LoadLevel.PEAK));
		return MonthlyCost.of(base, peak, inputs.peakHours());
	}

	/**
	 * The hours of the month billed at the peak: the workload's, or none for a pattern
	 * whose peak is its base, which is one load all month.
	 * @param document the design
	 * @return the hours
	 */
	private static double billedPeakHours(ArchitectureDocument document) {
		Workload workload = document.workload();
		return hasPeak(workload) ? workload.peakHoursPerMonth() : 0.0;
	}

	private static boolean hasPeak(Workload workload) {
		return LoadLevel.PEAK.rate(workload) != LoadLevel.BASE.rate(workload);
	}

}
