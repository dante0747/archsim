package io.archsim.model.component.cache;

import java.util.Map;
import java.util.OptionalLong;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.DefaultFrom;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.OmitWhenDefault;
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
public record CacheConfig(@Default("redis") CacheTechnology technology, @Default("fixed_ratio") HitModel hitModel,
		@Default("0.8") @Ge(0) @Le(1) double hitRatio, @Default("60.0") @Gt(0) @Le(86_400) double ttlSeconds,
		@Default("true") boolean populateOnMiss,

		@DefaultFrom("DEFAULT_SERVICE_TIME") Distribution serviceTimeMs,

		@Default("1") @Ge(1) @Le(1024) int concurrency, @Default("1") @Ge(1) @Le(1000) int shards,
		@Default("auto") Toggle singleThreaded, @Default("auto") EvictionPolicy evictionPolicy,
		@Default("auto") Persistence persistence, @Default("60.0") @Gt(0) @Le(86_400) double snapshotIntervalS,
		@Default("10.0") @Ge(0) @Le(10_000) double forkMsPerGib,
		@Default("10000") @Ge(0) @Le(100_000) int queueCapacity, @Gt(0) @Le(600_000) @Nullable Double queueTimeoutMs,
		@Gt(0) @Le(Resources.MAX_CPU_CORES) @Nullable Double cpuCores,
		@OmitWhenDefault @Gt(0) @Le(1) @Nullable Double siblingSpeed,
		@Gt(0) @Le(Resources.MAX_MEMORY_GIB) @Nullable Double memoryGib,
		@Matches(Resources.INSTANCE_TYPE) @Nullable String instanceType,
		@Default("1.0") @Gt(0) @Le(1_048_576) double itemSizeKb,
		@Default("0.1") @Ge(0) @Le(100_000) double costPerHour) {

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
	static final TechnologyProfiles<CacheTechnology> PROFILES = new TechnologyProfiles<>(CacheTechnology.class,
			Map.of(CacheTechnology.REDIS, kind(TechnologyProfile.core(), "on").with("persistence", "none"),
					CacheTechnology.VALKEY, kind(TechnologyProfile.experimental(), "on").with("persistence", "none"),
					CacheTechnology.MEMCACHED,
					kind(TechnologyProfile.experimental(), "off").with("persistence", "none"), CacheTechnology.CDN,
					kind(TechnologyProfile.experimental(), "off"), CacheTechnology.VARNISH,
					kind(TechnologyProfile.experimental(), "off"), CacheTechnology.GENERIC,
					kind(TechnologyProfile.core(), "off")));

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

}
