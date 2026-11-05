package io.archsim.engine.estimate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import io.archsim.engine.host.HostSpeeds;
import io.archsim.engine.load.CacheMisses;
import io.archsim.engine.load.LoadEstimate;
import io.archsim.engine.load.LoadEstimator;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.queueing.LognormalMixture;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.Workload;
import io.archsim.model.text.PythonFormat;

/**
 * The instant estimate: what textbook queueing formulas predict for a design at its peak,
 * without simulating it. Each part's utilization is its load over its slots' or CPU's
 * capacity; its wait an M/M/c queue per pool; each request type's latency the sum of the
 * network, waits and own times along its path; the p95 a rough log-normal approximation;
 * and a part at its limit or above overloads the design. Not included: retries, timeouts,
 * faults, lock waits, buffer-cache misses, cold starts, GC pauses, autoscaling delays and
 * the memory model, which the simulation includes.
 */
public final class InstantEstimator {

	/** How the estimate is made. */
	public static final String METHOD = "Estimate from queueing formulas (not simulated)";

	/** What the estimate leaves out. */
	public static final String NOT_INCLUDED = "Queueing formulas (M/M/c) at the peak load, not a simulation: first "
			+ "attempts only, with no retries, timeouts, faults, lock waits, buffer-cache misses, cold starts, GC "
			+ "pauses or autoscaling delays.";

	/** How rough the p95 is. */
	private static final String P95_NOTE = "The p95 is a rough approximation: a lognormal with each request path's "
			+ "mean and variance, mixed by traffic share.";

	/** The parts whose CPU work runs on a machine's threads. */
	private static final List<String> MACHINE_TYPES = List.of(ServiceSpec.TYPE, DatabaseSpec.TYPE, CacheSpec.TYPE);

	private final ComponentRegistry registry;

	private final LoadEstimator loads;

	/**
	 * Estimates designs made of a registry's types.
	 * @param registry the component types
	 */
	public InstantEstimator(ComponentRegistry registry) {
		this.registry = registry;
		this.loads = new LoadEstimator(registry);
	}

	/**
	 * The instant estimate of a design.
	 * @param resolved the design as a run simulates it: resolved and auto-sized
	 * @return the estimate at the workload's peak
	 */
	public InstantEstimate estimate(ArchitectureDocument resolved) {
		Workload workload = resolved.workload();
		double peak = workload.peakRate();
		EstimateGraph graph = EstimateGraph.of(resolved.architecture(), this.registry);
		Map<String, Double> misses = this.loads.ttlMissShares(resolved.architecture(), workload, peak);
		LoadEstimate load = this.loads.estimate(resolved.architecture(), workload, peak, new CacheMisses(misses, null));
		EstimateGraph.RequestPath path = graph.requestPath();
		PartEstimates parts = PartEstimates.of(graph, load, speeds(resolved, graph, load, peak), path.parts());
		return result(parts, ClientMix.of(graph, load, parts, path), workload, hitRatioNotes(graph, misses, workload));
	}

	/**
	 * The estimate, from the parts' estimates and the clients' paths.
	 * @param parts the parts' estimates
	 * @param mix the clients' paths
	 * @param workload the workload, for its peak and pattern
	 * @param hitRatioNotes what the TTL caches' hit ratios rest on
	 * @return the estimate
	 */
	private InstantEstimate result(PartEstimates parts, ClientMix mix, Workload workload, List<String> hitRatioNotes) {
		double peak = workload.peakRate();
		Saturation saturation = Saturation.of(parts.estimates().values(), peak);
		boolean pathOverloaded = saturation.pathOverloaded();
		Latency latency = (!mix.times().isEmpty() && mix.complete() && !pathOverloaded) ? latency(mix) : null;
		EstimatedPart busiest = saturation.busiest();
		return new InstantEstimate(METHOD, Rounded.of(peak, Rounded.RATE), latency, true,
				(busiest != null) ? busiest(busiest) : null, !saturation.overloaded().isEmpty(),
				Rounded.of(saturation.rps(), Rounded.SATURATION), EstimateText.summary(peak, saturation, latency),
				mix.paths(), mix.breakdown(), parts.inDesignOrder(mix.perRequestMs()),
				notes(hitRatioNotes, !pathOverloaded && !mix.complete(), workload));
	}

	private static Busiest busiest(EstimatedPart part) {
		return new Busiest(part.id(), part.label(), Saturation.orZero(part.utilization()),
				Objects.requireNonNull(part.limitedBy(), "a part with a utilization has a limit"));
	}

	private static Latency latency(ClientMix mix) {
		double mean = FloatSum.of(mix.times().stream().mapToDouble((time) -> time.weight() * time.time().mean()))
				/ mix.totalRps();
		OptionalDouble p95 = LognormalMixture.p95(mix.times());
		return new Latency(Rounded.of(mean, Rounded.RATE),
				Rounded.of(p95.isPresent() ? p95.getAsDouble() : null, Rounded.RATE));
	}

	/**
	 * The estimate's notes, in the Python engine's order: what it leaves out, how rough
	 * its p95 is, what the TTL caches' hit ratios rest on, why the latency is not
	 * estimated, and the pattern's peak.
	 * @param hitRatioNotes what the TTL caches' hit ratios rest on
	 * @param latencyUnknown whether the latency is not estimated because some parts' load
	 * depends on the run
	 * @param workload the workload, for its pattern and peak
	 * @return the notes
	 */
	private static List<String> notes(List<String> hitRatioNotes, boolean latencyUnknown, Workload workload) {
		List<String> notes = new ArrayList<>(List.of(NOT_INCLUDED, P95_NOTE));
		notes.addAll(hitRatioNotes);
		if (latencyUnknown) {
			notes.add("Latency not estimated: the load on some parts depends on the run.");
		}
		if (!"constant".equals(RecordCodec.tag(workload.pattern()))) {
			notes.add("At the pattern's peak of " + PythonFormat.format(workload.peakRate(), ",.0f")
					+ " req/s; a run's p95 also covers quieter periods.");
		}
		return notes;
	}

	/**
	 * What each TTL cache's estimated hit ratio rests on.
	 * @param graph the design
	 * @param misses each TTL cache's share of reads missed
	 * @param workload the workload, for its key count
	 * @return a note per TTL cache, in the design's order
	 */
	private List<String> hitRatioNotes(EstimateGraph graph, Map<String, Double> misses, Workload workload) {
		List<String> notes = new ArrayList<>();
		for (NodeSpec node : graph.architecture().nodes()) {
			Double missed = misses.get(node.id());
			if (missed != null) {
				OptionalLong held = heldKeys(node);
				String why = (held.isPresent() && held.getAsLong() < workload.keyCount())
						? "its memory holds the " + PythonFormat.format(held.getAsLong(), ",") + " most popular of "
								+ PythonFormat.format(workload.keyCount(), ",") + " keys"
						: "evictions not included";
				notes.add(graph.label(node.id()) + "'s hit ratio is estimated at "
						+ PythonFormat.format(1 - missed, ".0%") + " from its TTL and how popular the keys are (" + why
						+ ").");
			}
		}
		return notes;
	}

	private OptionalLong heldKeys(NodeSpec cache) {
		return this.registry.find(CacheSpec.TYPE)
			.flatMap((spec) -> EstimateGraph.parsed(spec, cache))
			.filter(CacheConfig.class::isInstance)
			.map(CacheConfig.class::cast)
			.map(CacheConfig::heldKeys)
			.orElse(OptionalLong.empty());
	}

	/**
	 * The speed of each part whose CPU threads slow each other down (schema 1.7): on the
	 * design's host, or on a part's own hyperthreads, at the estimated load.
	 * @param resolved the design
	 * @param graph its graph
	 * @param load the parts' loads
	 * @param peak the peak load
	 * @return the speed of each slowed part, by its id
	 */
	private static Map<String, Double> speeds(ArchitectureDocument resolved, EstimateGraph graph, LoadEstimate load,
			double peak) {
		return HostSpeeds.at(resolved.architecture(), load,
				(node) -> MACHINE_TYPES.contains(node.type())
						? graph.config(node.id()).map((config) -> LenientSettings.of(RecordCodec.write(config)))
						: Optional.empty(),
				peak);
	}

}
