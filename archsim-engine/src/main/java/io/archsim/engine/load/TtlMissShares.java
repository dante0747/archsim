package io.archsim.engine.load;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.codec.BindingException;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.document.Architecture;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.Workload;

/**
 * The share of reads each TTL cache misses, estimated from the reads and writes that
 * reach it, its TTL, the key popularity and the keys its memory holds. A cache whose load
 * depends on another TTL cache upstream is estimated after it; one whose load cannot be
 * estimated at all is left out.
 */
final class TtlMissShares {

	private static final String TTL_KEYSPACE = "ttl_keyspace";

	private final LoadEstimator estimator;

	private final ComponentRegistry registry;

	TtlMissShares(LoadEstimator estimator, ComponentRegistry registry) {
		this.estimator = estimator;
		this.registry = registry;
	}

	/**
	 * The share each TTL cache misses, in the order they were estimated.
	 * @param architecture the design's parts and links
	 * @param workload the design's workload: its key count and skew
	 * @param rate the clients' arrival rate
	 * @return the share of reads each cache misses, by its id
	 */
	Map<String, Double> of(Architecture architecture, Workload workload, double rate) {
		Optional<ComponentSpec<?>> found = this.registry.find(CacheSpec.TYPE);
		if (found.isEmpty()) {
			return Map.of();
		}
		ComponentSpec<?> cacheType = found.get();
		List<NodeSpec> ttlCaches = ttlCaches(architecture, cacheType);
		Map<String, Double> shares = new LinkedHashMap<>();
		for (int round = 0; round <= ttlCaches.size(); round++) {
			LoadEstimate loads = this.estimator.estimate(architecture, workload, rate, new CacheMisses(shares, null));
			List<NodeSpec> pending = ttlCaches.stream()
				.filter((cache) -> !shares.containsKey(cache.id()) && loads.node(cache.id()).isPresent())
				.toList();
			if (pending.isEmpty()) {
				break;
			}
			for (NodeSpec cache : pending) {
				NodeLoad load = loads.node(cache.id()).orElseThrow();
				shares.put(cache.id(), 1 - traffic(cache, cacheType, load, workload).hitRatio());
			}
		}
		return shares;
	}

	private static List<NodeSpec> ttlCaches(Architecture architecture, ComponentSpec<?> cacheType) {
		return architecture.nodes()
			.stream()
			.filter((node) -> node.type().equals(CacheSpec.TYPE)
					&& LenientSettings.ofNode(Optional.of(cacheType), node.config()).is("hitModel", TTL_KEYSPACE))
			.toList();
	}

	private TtlCacheTraffic traffic(NodeSpec cache, ComponentSpec<?> cacheType, NodeLoad load, Workload workload) {
		double ttlSeconds = LenientSettings.overDefaults(cacheType, cache.config()).number("ttlSeconds").orElse(0.0);
		return new TtlCacheTraffic(load.read(), load.write(), workload.keyCount(), workload.keySkew(), ttlSeconds,
				capacityKeys(cache, cacheType));
	}

	/**
	 * The keys a cache's memory holds over all its shards.
	 * @param cache the cache
	 * @param cacheType its component type
	 * @return the keys; {@code null} when memory is not set or the config is not valid
	 */
	private static @Nullable Long capacityKeys(NodeSpec cache, ComponentSpec<?> cacheType) {
		try {
			if (cacheType.parseConfig(cache.config()) instanceof CacheConfig config
					&& config.capacityKeys().isPresent()) {
				return config.capacityKeys().getAsLong() * Math.max(1, config.shards());
			}
			return null;
		}
		catch (BindingException ex) {
			// a config that is not valid is reported by validation
			return null;
		}
	}

}
