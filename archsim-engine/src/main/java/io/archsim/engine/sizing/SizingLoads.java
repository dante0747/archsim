package io.archsim.engine.sizing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.archsim.engine.host.HostSpeeds;
import io.archsim.engine.host.PartWork;
import io.archsim.engine.load.BaseRate;
import io.archsim.engine.load.CacheMisses;
import io.archsim.engine.load.LoadEstimate;
import io.archsim.engine.load.LoadEstimator;
import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.HostSpec;
import io.archsim.model.document.NodeSpec;

/**
 * What a design's parts carry at its peak and at its steady base rate (for CPU credits),
 * with each TTL cache's misses estimated from its TTL and the key popularity (all of them
 * when its load cannot be estimated), and each part's CPU speed on the design's host.
 *
 * @param peak the loads at the peak
 * @param base the loads at the base rate
 * @param peakSpeeds each slowed part's CPU speed at the peak
 * @param baseSpeeds each slowed part's CPU speed at the base rate
 */
record SizingLoads(LoadEstimate peak, LoadEstimate base, Map<String, Double> peakSpeeds,
		Map<String, Double> baseSpeeds) {

	/**
	 * The share of reads a TTL cache misses when its load cannot be estimated: all of
	 * them.
	 */
	private static final double UNESTIMATED_MISSES = 1.0;

	/** The parts whose CPU work runs on the design's host. */
	private static final Set<String> HOST_TYPES = Set.of("service", "database", "cache");

	/**
	 * The loads of a design.
	 * @param design the design, normalized
	 * @param estimator the load estimator
	 * @param registry the component types
	 * @return its loads at the peak and the base rate
	 */
	static SizingLoads of(ArchitectureDocument design, LoadEstimator estimator, ComponentRegistry registry) {
		LoadEstimate peak = at(design, estimator, design.workload().peakRate());
		double baseRate = BaseRate.of(design.workload());
		LoadEstimate base = at(design, estimator, baseRate);
		HostSpec host = design.architecture().host();
		if (host == null) {
			return new SizingLoads(peak, base, Map.of(), Map.of());
		}
		return new SizingLoads(peak, base,
				HostSpeeds.onHost(host, work(design, peak, registry), design.workload().peakRate()),
				HostSpeeds.onHost(host, work(design, base, registry), baseRate));
	}

	private static LoadEstimate at(ArchitectureDocument design, LoadEstimator estimator, double rate) {
		Map<String, Double> misses = estimator.ttlMissShares(design.architecture(), design.workload(), rate);
		return estimator.estimate(design.architecture(), design.workload(), rate,
				new CacheMisses(misses, UNESTIMATED_MISSES));
	}

	/** The CPU work of each service, database and cache whose load is known. */
	private static Map<String, PartWork> work(ArchitectureDocument design, LoadEstimate loads,
			ComponentRegistry registry) {
		Map<String, PartWork> work = new LinkedHashMap<>();
		for (NodeSpec node : design.architecture().nodes()) {
			Optional<ComponentSpec<?>> type = registry.find(node.type());
			Optional<NodeLoad> load = loads.node(node.id());
			if (HOST_TYPES.contains(node.type()) && type.isPresent() && load.isPresent()) {
				PartWork.forSizing(node.type(), LenientSettings.overDefaults(type.get(), node.config()), load.get())
					.ifPresent((part) -> work.put(node.id(), part));
			}
		}
		return work;
	}

}
