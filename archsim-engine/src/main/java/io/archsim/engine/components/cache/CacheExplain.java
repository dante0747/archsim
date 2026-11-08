package io.archsim.engine.components.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.text.NumberText;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.text.PythonFormat;

/**
 * The calculations behind a cache's own figures: its command capacity, its hit ratio when
 * reads reached it, and the keys that fit in its memory when they are bounded.
 */
final class CacheExplain {

	private static final double MS_PER_SECOND = 1000.0;

	private static final int SERVICE_DIGITS = 3;

	private CacheExplain() {
	}

	/**
	 * A cache's formulas, in the report's order.
	 * @param config its settings
	 * @param shards its shards
	 * @param summary its summary in the report
	 * @return its command capacity's, its hit ratio's and its key capacity's
	 */
	static List<Formula> of(CacheConfig config, CacheShards shards, NodeSummary summary) {
		List<Formula> formulas = new ArrayList<>();
		formulas.add(commandCapacity(config, shards));
		long hits = summary.counter(NodeMetrics.HITS);
		long misses = summary.counter(NodeMetrics.MISSES);
		if (hits + misses > 0) {
			formulas.add(hitRatio(hits, misses));
		}
		OptionalLong capacity = shards.capacity();
		if (capacity.isPresent()) {
			formulas.add(keyCapacity(config, shards, capacity.getAsLong()));
		}
		return formulas;
	}

	private static Formula commandCapacity(CacheConfig config, CacheShards shards) {
		double serviceMs = config.serviceTimeMs().expected();
		int count = shards.all().size();
		@Nullable Double capacity = (serviceMs != 0) ? shards.executors() * count * MS_PER_SECOND / serviceMs : null;
		return Formula
			.of("command_capacity", "Command capacity", "capacity = shards × executors per shard × 1000 ÷ command time",
					count + " × " + shards.executors() + " × 1000 ÷ " + NumberText.formula(serviceMs, SERVICE_DIGITS)
							+ " ms")
			.withResult(capacity, "commands/s")
			.withNote(config.runsOnOneThread() ? "Redis and Valkey run commands on one thread per shard: adding cores "
					+ "does not raise this, adding shards does." : null)
			.withMetric("utilization");
	}

	private static Formula hitRatio(long hits, long misses) {
		long reads = hits + misses;
		String hitsText = PythonFormat.format(hits, ",");
		return Formula
			.of("hit_ratio", "Hit ratio", "hit ratio = hits ÷ (hits + misses)",
					hitsText + " ÷ (" + hitsText + " + " + PythonFormat.format(misses, ",") + ")")
			.withResult((double) hits / reads, "")
			.withNote("Each miss also costs a read on the data store behind the cache ("
					+ NumberText.percent((double) misses / reads) + " of reads).")
			.withMetric("hitRatio");
	}

	private static Formula keyCapacity(CacheConfig config, CacheShards shards, long capacity) {
		double share = CacheConfig.KEY_MEMORY_SHARE;
		return Formula
			.of("key_capacity", "Keys that fit",
					"keys = shards × " + PythonFormat.format(share, ".0%") + " of memory ÷ item size",
					shards.all().size() + " × " + PythonFormat.format(share, "g") + " × "
							+ NumberText.formula(config.memoryGib(), 1) + " GiB ÷ "
							+ NumberText.formula(config.itemSizeKb(), 1) + " KiB")
			.withResult((double) capacity, "keys")
			.withMetric("memoryUsedRatio");
	}

}
