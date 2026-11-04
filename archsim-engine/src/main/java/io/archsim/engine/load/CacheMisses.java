package io.archsim.engine.load;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The share of reads TTL caches miss, where it is known before a run (a cache with a
 * fixed hit ratio needs none).
 *
 * @param byCache the share each estimated TTL cache misses, by its id
 * @param otherwise the share any other TTL cache misses; {@code null}: it depends on the
 * run
 */
public record CacheMisses(Map<String, Double> byCache, @Nullable Double otherwise) {

	/** Nothing known: every TTL cache's misses depend on the run. */
	public static final CacheMisses UNKNOWN = new CacheMisses(Map.of(), null);

	/**
	 * Copies the shares, so the record does not change.
	 */
	public CacheMisses {
		byCache = Map.copyOf(byCache);
	}

	/**
	 * The share of reads a TTL cache misses.
	 * @param cacheId the cache's id
	 * @return the share, or {@code null} when it depends on the run
	 */
	@Nullable Double of(String cacheId) {
		Double known = this.byCache.get(cacheId);
		return (known != null) ? known : this.otherwise;
	}

}
