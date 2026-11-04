package io.archsim.engine.sizing;

import java.util.Map;

import io.archsim.engine.sizing.Placement.Work;
import io.archsim.engine.sizing.Steps.Explained;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.document.Workload;

/**
 * The size of a cache node: CPU for its commands at the peak, capped at the commands it
 * runs at once; memory for its share of the keys, of which
 * {@link CacheConfig#KEY_MEMORY_SHARE} of memory holds keys.
 */
final class CacheSizer {

	private static final double KIB_PER_GIB = 1024 * 1024;

	private static final double MILLIS_PER_SECOND = 1000;

	/** The least auto memory of a cache node, in GiB. */
	private static final double MIN_MEMORY_GIB = 0.5;

	private CacheSizer() {
	}

	/**
	 * The size of one cache node.
	 * @param config the cache's settings
	 * @param totalRps the commands per second all its nodes run at the peak
	 * @param workload the design's workload, for its key count
	 * @param placement where it runs
	 * @return its size
	 */
	static PartSize size(CacheConfig config, double totalRps, Workload workload, Placement placement) {
		int shards = Math.max(1, config.shards());
		int executors = Math.max(1, config.executors());
		double ms = config.serviceTimeMs().expected();
		double perShard = totalRps / shards;
		Work work = placement.atSpeed(perShard * ms / MILLIS_PER_SECOND,
				"%s commands/s per node × %s ms".formatted(SizingText.brief(perShard), SizingText.brief(ms)));
		Explained cpu = Steps.capped(Steps.cpu(work.busy(), work.intro()), executors,
				"it runs %d command%s at a time (add shards)".formatted(executors, SizingText.plural(executors)));
		double data = workload.keyCount() * config.itemSizeKb() / shards / KIB_PER_GIB;
		String over = (shards > 1) ? " over " + shards + " shards" : "";
		String memoryWhy = "%s keys × %s KiB%s = %s per node; %s of memory holds keys, %s".formatted(
				SizingText.brief(workload.keyCount()), SizingText.brief(config.itemSizeKb()), over,
				SizingText.gib(data), SizingText.percent(CacheConfig.KEY_MEMORY_SHARE), SizingText.headroom());
		Map<Need, Double> need = Map.of(Need.BUSY_CORES, work.busy(), Need.CPU_CORES, work.busy() / Sizing.TARGET_CPU,
				Need.MEMORY_GIB, data / CacheConfig.KEY_MEMORY_SHARE);
		double memory = Steps.memory(data / CacheConfig.KEY_MEMORY_SHARE * Sizing.MEMORY_HEADROOM, MIN_MEMORY_GIB);
		return new PartSize(cpu.value(), memory, null, 0.0,
				Map.of(Spec.CPU_CORES, cpu.why(), Spec.MEMORY_GIB, memoryWhy), null, null, need, null, false);
	}

}
