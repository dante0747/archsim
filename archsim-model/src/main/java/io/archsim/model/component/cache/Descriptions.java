package io.archsim.model.component.cache;

import io.archsim.model.component.Resources;

/**
 * What each setting of a cache means, as the editor's inspector and the component catalog
 * show it.
 */
final class Descriptions {

	static final String CACHE_BEHAVIOUR_GROUP = "Cache behaviour";

	static final String CAPACITY_GROUP = "Capacity";

	static final String COST_GROUP = "Cost";

	static final String PERFORMANCE_GROUP = "Performance";

	static final String RESOURCES_GROUP = "Resources";

	static final String TECHNOLOGY_BEHAVIOUR_GROUP = "Technology behaviour";

	static final String TECHNOLOGY_GROUP = "Technology";

	static final String TECHNOLOGY = "The cache product. It sets how commands execute (one thread for Redis and "
			+ "Valkey, worker threads for Memcached) and the eviction behaviour, shown under Technology "
			+ "behaviour. Presets also fill in typical numbers.";

	static final String HIT_MODEL = "fixed_ratio: each read hits with the given probability (simplest assumption). "
			+ "ttl_keyspace: reads pick keys from the workload's Zipf keyspace; a key hits if it was filled less "
			+ "than TTL seconds ago (derives hit rate, including cold start).";

	static final String HIT_RATIO = "Probability a read is served from the cache (fixed_ratio model).";

	static final String TTL_SECONDS = "Time a filled key stays valid (ttl_keyspace model). Capacity is unbounded (no "
			+ "eviction).";

	static final String POPULATE_ON_MISS = "After a miss is served by the data store, write the value back (one extra "
			+ "cache operation).";

	static final String SERVICE_TIME_MS = "Time to execute one cache command, excluding network (ms).";

	static final String CONCURRENCY = "Commands executed in parallel per shard (Memcached threads, CDN or Varnish "
			+ "parallelism). Ignored when commands run single-threaded (Redis, Valkey).";

	static final String SHARDS = "Cache nodes the keys are split across (Redis Cluster shards, Memcached servers). "
			+ "Each has its own executors and memory; keys hash to a shard, so a hot key loads one.";

	static final String SINGLE_THREADED = "Execute commands one at a time per shard, whatever the cores. Auto: on for "
			+ "Redis and Valkey.";

	static final String EVICTION_POLICY = "When memory is full (TTL model with memory set): allkeys_lru evicts the "
			+ "least recently used key; noeviction (Redis's own default) refuses new keys with an out-of-memory "
			+ "error, so missed reads cannot be cached. Auto: allkeys_lru.";

	static final String PERSISTENCE = "rdb: snapshot to disk every interval; forking the process blocks commands for "
			+ "about the fork time per GiB of data. Auto: none (cache use).";

	static final String SNAPSHOT_INTERVAL_S = "Time between RDB snapshots (s).";

	static final String FORK_MS_PER_GIB = "Command stall per GiB of data while forking for a snapshot (ms). Typical "
			+ "Linux hosts: around 10; some virtual machines much more.";

	static final String QUEUE_CAPACITY = "Commands that may wait for an executor; more are rejected.";

	static final String QUEUE_TIMEOUT_MS = "Commands waiting longer than this are rejected (ms). Empty = no limit.";

	static final String CPU_CORES = Resources.CPU_PER + "cache node" + Resources.NOT_MODELLED_WHEN_EMPTY
			+ Resources.CPU_CONTENTION;

	static final String MEMORY_GIB = Resources.MEMORY_PER + "cache node" + Resources.IN_GIB
			+ Resources.NOT_MODELLED_WHEN_EMPTY
			+ "80% of it holds keys. TTL model: when it is full the least recently used key is evicted. Fixed hit "
			+ "ratio: checked against the workload's keyspace.";

	static final String INSTANCE_TYPE = Resources.INSTANCE_TYPE_OF_EACH + "cache node" + Resources.INSTANCE_TYPE_USE;

	static final String ITEM_SIZE_KB = "Average size of one cached key and value, with overhead (KiB). Only used when "
			+ "memory is set.";

	static final String COST_PER_HOUR = "Price of the cache per hour, used for cost estimates only. Illustrative "
			+ "default.";

	private Descriptions() {
	}

}
