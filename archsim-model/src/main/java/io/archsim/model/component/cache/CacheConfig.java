package io.archsim.model.component.cache;

import java.util.OptionalLong;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.DefaultFrom;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.OmitWhenDefault;
import io.archsim.model.codec.Ui;
import io.archsim.model.component.Resources;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.component.TechnologyProfiles;
import io.archsim.model.component.Toggle;
import io.archsim.model.distribution.Distribution;
import io.archsim.model.distribution.Exponential;

/**
 * A cache: how often reads hit, how fast commands run, its shards and its memory.
 *
 * @param technology the cache product
 * @param hitModel a fixed hit ratio, or keys that expire after a TTL
 * @param hitRatio the share of reads that hit, with a fixed ratio
 * @param ttlSeconds how long a key lives, with the TTL model
 * @param populateOnMiss whether a miss fills the key, with the TTL model
 * @param serviceTimeMs the time one command takes
 * @param concurrency commands one shard executes at once
 * @param shards cache nodes, keys spread over them
 * @param singleThreaded whether commands run on one thread; auto follows the technology
 * @param evictionPolicy what a full cache does; auto follows the technology
 * @param persistence whether it snapshots to disk; auto follows the technology
 * @param snapshotIntervalS how often a snapshot is taken
 * @param forkMsPerGib the pause per GiB a snapshot's fork takes
 * @param queueCapacity commands that may wait per shard
 * @param queueTimeoutMs how long a command may wait; {@code null}: no limit
 * @param cpuCores vCPU per shard; {@code null}: not modelled
 * @param siblingSpeed a vCPU's speed while its core's other vCPU is busy too (schema 1.7)
 * @param memoryGib memory per shard; {@code null}: not modelled
 * @param instanceType the instance type in the design's price snapshot (schema 1.4)
 * @param itemSizeKb the size of one cached item
 * @param costPerHour the price per hour, in USD
 */
public record CacheConfig(
		@Default("redis") @Description(Descriptions.TECHNOLOGY) @Ui(
				group = Descriptions.TECHNOLOGY_GROUP) CacheTechnology technology,
		@Default("fixed_ratio") @Description(Descriptions.HIT_MODEL) @Ui(group = Descriptions.CACHE_BEHAVIOUR_GROUP,
				headline = true) HitModel hitModel,
		@Default("0.8") @Description(Descriptions.HIT_RATIO) @Ui(group = Descriptions.CACHE_BEHAVIOUR_GROUP,
				assumption = true, headline = true, visibleWhen = "hitModel=fixed_ratio",
				step = 0.05) @Ge(0) @Le(1) double hitRatio,
		@Default("60.0") @Description(Descriptions.TTL_SECONDS) @Ui(label = "TTL", unit = "s",
				group = Descriptions.CACHE_BEHAVIOUR_GROUP, assumption = true,
				visibleWhen = "hitModel=ttl_keyspace") @Gt(0) @Le(86_400) double ttlSeconds,
		@Default("true") @Description(Descriptions.POPULATE_ON_MISS) @Ui(
				group = Descriptions.CACHE_BEHAVIOUR_GROUP) boolean populateOnMiss,

		@DefaultFrom("DEFAULT_SERVICE_TIME") @Description(Descriptions.SERVICE_TIME_MS) @Ui(label = "Command time",
				unit = "ms", group = Descriptions.PERFORMANCE_GROUP, assumption = true) Distribution serviceTimeMs,

		@Default("1") @Description(Descriptions.CONCURRENCY) @Ui(label = "Command executors",
				group = Descriptions.CAPACITY_GROUP) @Ge(1) @Le(1024) int concurrency,
		@Default("1") @Description(Descriptions.SHARDS) @Ui(label = "Shards (cluster nodes)",
				group = Descriptions.CAPACITY_GROUP) @Ge(1) @Le(1000) int shards,
		@Default("auto") @Description(Descriptions.SINGLE_THREADED) @Ui(label = "Single-threaded commands",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) Toggle singleThreaded,
		@Default("auto") @Description(Descriptions.EVICTION_POLICY) @Ui(
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) EvictionPolicy evictionPolicy,
		@Default("auto") @Description(Descriptions.PERSISTENCE) @Ui(
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) Persistence persistence,
		@Default("60.0") @Description(Descriptions.SNAPSHOT_INTERVAL_S) @Ui(label = "Snapshot interval", unit = "s",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP,
				visibleWhen = "persistence=rdb") @Gt(0) @Le(86_400) double snapshotIntervalS,
		@Default("10.0") @Description(Descriptions.FORK_MS_PER_GIB) @Ui(label = "Fork stall per GiB", unit = "ms",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP, assumption = true,
				visibleWhen = "persistence=rdb") @Ge(0) @Le(10_000) double forkMsPerGib,
		@Default("10000") @Description(Descriptions.QUEUE_CAPACITY) @Ui(label = "Queue limit",
				group = Descriptions.CAPACITY_GROUP) @Ge(0) @Le(100_000) int queueCapacity,
		@Description(Descriptions.QUEUE_TIMEOUT_MS) @Ui(label = "Queue timeout", unit = "ms",
				group = Descriptions.CAPACITY_GROUP) @Gt(0) @Le(600_000) @Nullable Double queueTimeoutMs,
		@Description(Descriptions.CPU_CORES) @Ui(label = "CPU", unit = "vCPU", group = Descriptions.RESOURCES_GROUP,
				step = 0.5) @Gt(0) @Le(Resources.MAX_CPU_CORES) @Nullable Double cpuCores,
		@Description(Resources.SIBLING_SPEED) @Ui(label = "Hyperthread sibling speed",
				group = Descriptions.RESOURCES_GROUP,
				step = 0.05) @OmitWhenDefault @Gt(0) @Le(1) @Nullable Double siblingSpeed,
		@Description(Descriptions.MEMORY_GIB) @Ui(label = "Memory", unit = "GiB", group = Descriptions.RESOURCES_GROUP,
				step = 0.5) @Gt(0) @Le(Resources.MAX_MEMORY_GIB) @Nullable Double memoryGib,
		@Description(Descriptions.INSTANCE_TYPE) @Ui(
				group = Descriptions.RESOURCES_GROUP) @Matches(Resources.INSTANCE_TYPE) @Nullable String instanceType,
		@Default("1.0") @Description(Descriptions.ITEM_SIZE_KB) @Ui(label = "Item size", unit = "KiB",
				group = Descriptions.RESOURCES_GROUP, assumption = true) @Gt(0) @Le(1_048_576) double itemSizeKb,
		@Default("0.1") @Description(Descriptions.COST_PER_HOUR) @Ui(unit = "$/h", group = Descriptions.COST_GROUP,
				assumption = true) @Ge(0) @Le(100_000) double costPerHour) {

	/**
	 * The share of a cache node's memory that holds keys; the rest is overhead
	 * (illustrative).
	 */
	public static final double KEY_MEMORY_SHARE = 0.8;

	/** The KiB in a GiB, to count the items that fit in a shard's memory. */
	static final int KIB_PER_GIB = 1024 * 1024;

	/** The default time of one command: 0.05 ms on average. */
	static final Distribution DEFAULT_SERVICE_TIME = new Exponential(0.05);

	/** What each technology does by default (technology.py's {@code CACHE_PROFILES}). */
	static final TechnologyProfiles<CacheTechnology> PROFILES = TechnologyProfiles.builder(CacheTechnology.class)
		.with(CacheTechnology.REDIS, kind(TechnologyProfile.core(), "on").with("persistence", "none"))
		.with(CacheTechnology.VALKEY, kind(TechnologyProfile.experimental(), "on").with("persistence", "none"))
		.with(CacheTechnology.MEMCACHED, kind(TechnologyProfile.experimental(), "off").with("persistence", "none"))
		.with(CacheTechnology.CDN, kind(TechnologyProfile.experimental(), "off"))
		.with(CacheTechnology.VARNISH, kind(TechnologyProfile.experimental(), "off"))
		.with(CacheTechnology.GENERIC, kind(TechnologyProfile.core(), "off"))
		.build();

	private static TechnologyProfile kind(TechnologyProfile tier, String singleThreaded) {
		return tier.with("singleThreaded", singleThreaded).with("evictionPolicy", "allkeys_lru");
	}

	public TechnologyProfile profile() {
		return PROFILES.of(this.technology);
	}

	/**
	 * Whether commands run on one thread per shard, after the technology's default.
	 * @return {@code true} when a shard executes one command at a time
	 */
	public boolean runsOnOneThread() {
		return profile().choice("singleThreaded", this.singleThreaded, Toggle.AUTO, Toggle.OFF) == Toggle.ON;
	}

	/**
	 * Whether a read can miss: keys expire, or the hit ratio is below 1.
	 * @return {@code true} when some reads miss
	 */
	public boolean canMiss() {
		return this.hitModel == HitModel.TTL_KEYSPACE || this.hitRatio < 1.0;
	}

	/**
	 * Commands one shard executes at once.
	 * @return one on a single thread, else the configured concurrency
	 */
	public int executors() {
		return runsOnOneThread() ? 1 : this.concurrency;
	}

	/**
	 * The keys that fit in {@link #KEY_MEMORY_SHARE} of a shard's memory; empty when
	 * memory is not set.
	 * @return the keys a shard holds, at least one
	 */
	public OptionalLong capacityKeys() {
		if (this.memoryGib == null) {
			return OptionalLong.empty();
		}
		double keyMemoryKb = KEY_MEMORY_SHARE * this.memoryGib * KIB_PER_GIB;
		return OptionalLong.of(Math.max(1, (long) Math.floor(keyMemoryKb / this.itemSizeKb)));
	}

	/**
	 * The keys its memory holds over all its shards; empty when memory is not set.
	 * @return the keys all the shards hold
	 */
	public OptionalLong heldKeys() {
		OptionalLong perShard = capacityKeys();
		return perShard.isPresent() ? OptionalLong.of(perShard.getAsLong() * Math.max(1, this.shards))
				: OptionalLong.empty();
	}

}
