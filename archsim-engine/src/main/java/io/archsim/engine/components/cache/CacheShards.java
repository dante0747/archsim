package io.archsim.engine.components.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.runtime.Cores;
import io.archsim.engine.runtime.KeyShards;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.SimContext;
import io.archsim.engine.runtime.WorkerPool;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.component.cache.EvictionPolicy;

/**
 * A cache's shards, each with its own executors, CPU and keys; a key hashes to one, so a
 * hot key loads one shard. With the TTL model and a memory size, each shard holds as many
 * keys as fit in its memory.
 */
final class CacheShards {

	private final List<CacheShard> shards;

	private final int executors;

	private final @Nullable Gauge coreBusy;

	private final OptionalLong capacityPerShard;

	/**
	 * A cache's shards at the start of a run.
	 * @param context the run
	 * @param config the cache's settings
	 * @param metrics the cache's metrics, which its shards' queues and keys count in
	 */
	CacheShards(SimContext context, CacheConfig config, NodeMetrics metrics) {
		this.executors = config.executors();
		this.coreBusy = (config.cpuCores() != null) ? new Gauge(context.timeline()) : null;
		this.capacityPerShard = config.keysExpire() ? config.capacityKeys() : OptionalLong.empty();
		EvictionPolicy policy = config.evictionInEffect();
		WorkerPool.Queue queue = new WorkerPool.Queue(config.queueCapacity(), config.queueTimeoutMs());
		List<CacheShard> built = new ArrayList<>(config.shards());
		for (int shard = 0; shard < config.shards(); shard++) {
			WorkerPool pool = new WorkerPool(context.simulation(), this.executors, queue, metrics);
			Cores cores = new Cores(context.simulation(), config.cpuCores(), this.executors,
					Cores.Accounts.ofPart(this.coreBusy, context.machineFor(config.cpuCores(), config.siblingSpeed())));
			built.add(new CacheShard(pool, cores, new ShardKeys(this.capacityPerShard, policy, metrics)));
		}
		this.shards = List.copyOf(built);
	}

	/**
	 * The shard a key hashes to.
	 * @param key the key
	 * @return its shard
	 */
	CacheShard of(int key) {
		if (this.shards.size() == 1) {
			return this.shards.getFirst();
		}
		return this.shards.get(KeyShards.of(key, this.shards.size()));
	}

	List<CacheShard> all() {
		return this.shards;
	}

	/**
	 * The commands one shard executes at once.
	 * @return its executors
	 */
	int executors() {
		return this.executors;
	}

	/**
	 * The shards' busy CPU executors, when the cache has a CPU size.
	 * @return the gauge, or {@code null} without a CPU size
	 */
	@Nullable Gauge coreBusy() {
		return this.coreBusy;
	}

	/**
	 * The keys the shards' memory holds, with the TTL model and a memory size.
	 * @return the keys, over every shard; empty otherwise
	 */
	OptionalLong capacity() {
		return this.capacityPerShard.isPresent()
				? OptionalLong.of(this.capacityPerShard.getAsLong() * this.shards.size()) : OptionalLong.empty();
	}

	/**
	 * The keys the shards hold now, expired ones included.
	 * @return the keys, over every shard
	 */
	long keysCached() {
		return this.shards.stream().mapToLong((shard) -> shard.keys().size()).sum();
	}

	/**
	 * Ends the busy executors' series at the end of the run.
	 * @param end when the run stopped
	 */
	void finish(double end) {
		if (this.coreBusy != null) {
			this.coreBusy.finish(end);
		}
	}

}
