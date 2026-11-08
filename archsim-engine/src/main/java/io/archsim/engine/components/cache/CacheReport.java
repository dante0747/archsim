package io.archsim.engine.components.cache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.SequencedMap;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.metrics.Counter;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.runtime.Cores;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.model.catalog.ComponentCatalog;
import io.archsim.model.catalog.TechnologyTrait;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.component.cache.CacheSpec;

/**
 * What a cache adds to its part of a run's report: its CPU's utilization and ceiling, its
 * keys, its executors, the series behind them, and the behaviours its technology brings
 * that are in effect.
 */
final class CacheReport {

	private static final Map<String, List<TechnologyTrait>> TRAITS = ComponentCatalog.technologyTraits(CacheSpec.TYPE);

	private static final double MS_PER_SECOND = 1000.0;

	private CacheReport() {
	}

	/**
	 * The cache's own summary figures: its cores' utilization when it has a CPU size, its
	 * keys when their capacity is bounded, and its executors per shard.
	 * @param shards its shards
	 * @return the figures, in the report's order
	 */
	static SequencedMap<String, @Nullable Number> extraSummary(CacheShards shards) {
		SequencedMap<String, @Nullable Number> figures = new LinkedHashMap<>();
		Gauge busy = shards.coreBusy();
		if (busy != null) {
			figures.put("cpuCoreUtilization",
					Cores.utilization(busy, firstCores(shards).executors() * shards.all().size()).orElse(null));
		}
		OptionalLong capacity = shards.capacity();
		if (capacity.isPresent()) {
			figures.put("capacityKeys", capacity.getAsLong());
			figures.put("keysCached", shards.keysCached());
		}
		figures.put("executorsPerShard", shards.executors());
		return figures;
	}

	/**
	 * The cache's own series: its busy cores, its evictions, its memory in use and its
	 * forks' stalls, each when it is modelled.
	 * @param shards its shards
	 * @param upkeep its memory's sampling and its snapshots
	 * @param metrics its metrics
	 * @return the series over the whole timeline, in the report's order
	 */
	static SequencedMap<String, List<@Nullable Double>> extraTimeseries(CacheShards shards, CacheUpkeep upkeep,
			NodeMetrics metrics) {
		SequencedMap<String, List<@Nullable Double>> series = new LinkedHashMap<>();
		Gauge busy = shards.coreBusy();
		if (busy != null) {
			series.put("cpuBusyCores", Cores.busyCores(busy, firstCores(shards).speed()));
		}
		Counter evictions = metrics.counters().get(ShardKeys.EVICTIONS);
		if (evictions != null) {
			series.put("evictionRps", RuntimeComponent.series(evictions.rateSeries()));
		}
		if (shards.capacity().isPresent()) {
			series.put("memoryUsedRatio", upkeep.memoryUsed());
		}
		if (upkeep.snapshots()) {
			series.put("forkPauseMs", RuntimeComponent.series(upkeep.forkPauseMs()));
		}
		return series;
	}

	/**
	 * The ceiling the cache's CPU sets: every shard's usable cores, as many as it has or
	 * as its executors use, busy with commands of the window's mean time, at the speed of
	 * their machine's threads.
	 * @param config the cache's settings
	 * @param shards its shards
	 * @param metrics its metrics
	 * @param serviceMs the mean command time, if any command ran
	 * @return the ceiling in requests per second, when the cache has a CPU size
	 */
	static SequencedMap<String, Double> resourceCapacity(CacheConfig config, CacheShards shards, NodeMetrics metrics,
			OptionalDouble serviceMs) {
		SequencedMap<String, Double> ceilings = new LinkedHashMap<>();
		Double cores = config.cpuCores();
		if (cores == null || serviceMs.orElse(0.0) == 0) {
			return ceilings;
		}
		double usable = Math.min(cores, shards.executors()) * shards.all().size();
		double fullSpeed = usable * MS_PER_SECOND / serviceMs.getAsDouble();
		ceilings.put("cpu", firstCores(shards).machine().capacity(fullSpeed, usable, metrics.arrivals().windowRate()));
		return ceilings;
	}

	/**
	 * The behaviours the cache's technology brings that are in effect: commands on one
	 * thread, eviction with the TTL model and a memory size, and snapshot forks.
	 * @param config the cache's settings
	 * @param shards its shards
	 * @param upkeep its snapshots
	 * @return the traits in effect, in the technology's order
	 */
	static List<TechnologyTrait> behaviour(CacheConfig config, CacheShards shards, CacheUpkeep upkeep) {
		List<TechnologyTrait> traits = TRAITS.getOrDefault(config.technology().json(), List.of());
		Map<String, Boolean> active = Map.of("single_thread", shards.executors() == 1 && config.runsOnOneThread(),
				"eviction", shards.capacity().isPresent(), "fork_stall", upkeep.snapshots());
		return traits.stream().filter((trait) -> active.getOrDefault(trait.id(), true)).toList();
	}

	private static Cores firstCores(CacheShards shards) {
		return shards.all().getFirst().cores();
	}

}
