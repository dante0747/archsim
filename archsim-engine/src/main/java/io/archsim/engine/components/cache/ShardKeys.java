package io.archsim.engine.components.cache;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.OptionalLong;
import java.util.SequencedMap;

import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.model.component.cache.EvictionPolicy;

/**
 * The keys one cache shard holds with the TTL model, each with the time it expires. With
 * a memory size the shard holds at most its capacity, kept in order of use: a full shard
 * evicts the least recently used key for a new one, or, refusing new keys when full
 * (noeviction), first drops the expired keys among the 64 least recently used and refuses
 * the new key if it is still full (an out-of-memory error).
 */
final class ShardKeys {

	/** The counter of keys evicted to make room. */
	static final String EVICTIONS = "evictions";

	/** The counter of keys refused by a full cache that does not evict. */
	static final String OOM_ERRORS = "oomErrors";

	/**
	 * How many of the least recently used keys a full shard looks at for expired ones.
	 */
	private static final int EXPIRED_SCAN = 64;

	/**
	 * When each key expires; with a memory size, the least recently used key first, else
	 * in the order they were first filled.
	 */
	private final SequencedMap<Integer, Double> expiry = new LinkedHashMap<>();

	private final OptionalLong capacity;

	private final EvictionPolicy policy;

	private final NodeMetrics metrics;

	/**
	 * A shard's keys at the start of a run.
	 * @param capacity the keys its memory holds; empty when its memory is not set
	 * @param policy what a full shard does: evict the least recently used key, or refuse
	 * new keys
	 * @param metrics the cache's metrics, which count evictions and refusals
	 */
	ShardKeys(OptionalLong capacity, EvictionPolicy policy, NodeMetrics metrics) {
		this.capacity = capacity;
		this.policy = policy;
		this.metrics = metrics;
	}

	/**
	 * How many keys the shard holds, expired ones included.
	 * @return the keys
	 */
	int size() {
		return this.expiry.size();
	}

	/**
	 * Reads a key, which hits when the shard holds it unexpired. A hit makes the key the
	 * most recently used.
	 * @param key the key
	 * @param now the time of the read
	 * @return {@code true} for a hit
	 */
	boolean read(int key, double now) {
		Double expires = this.expiry.get(key);
		boolean hit = expires != null && expires > now;
		if (hit && this.capacity.isPresent()) {
			this.expiry.remove(key);
			this.expiry.put(key, expires);
		}
		return hit;
	}

	/**
	 * Drops a key, as a write does.
	 * @param key the key
	 */
	void invalidate(int key) {
		this.expiry.remove(key);
	}

	/**
	 * Caches a key until it expires, as the most recently used.
	 * @param key the key
	 * @param now the time it is cached
	 * @param expiresAt when it expires
	 * @return {@code false} when the shard is full and refuses new keys
	 */
	boolean fill(int key, double now, double expiresAt) {
		if (this.capacity.isEmpty()) {
			this.expiry.put(key, expiresAt);
			return true;
		}
		long held = this.capacity.getAsLong();
		boolean full = !this.expiry.containsKey(key) && this.expiry.size() >= held;
		if (full && this.policy == EvictionPolicy.NOEVICTION) {
			dropExpired(now);
			if (this.expiry.size() >= held) {
				this.metrics.count(OOM_ERRORS, now);
				return false;
			}
		}
		this.expiry.remove(key);
		this.expiry.put(key, expiresAt);
		while (this.expiry.size() > held) {
			this.expiry.pollFirstEntry();
			this.metrics.count(EVICTIONS, now);
		}
		return true;
	}

	// as Redis reclaims expired keys before it refuses a new one
	private void dropExpired(double now) {
		Iterator<Double> expiries = this.expiry.values().iterator();
		for (int seen = 0; seen < EXPIRED_SCAN && expiries.hasNext(); seen++) {
			if (expiries.next() <= now) {
				expiries.remove();
			}
		}
	}

}
