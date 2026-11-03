package io.archsim.model.component.cache;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentIdentity;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.component.Port;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.issue.Issue;
import io.archsim.model.text.PythonFormat;

/**
 * A cache. Reads that hit return from memory. On a miss either the calling service reads
 * the data store itself (cache-aside: the miss port stays empty and the service's link to
 * the store reads only on a miss), or the cache asks the store linked to its miss port
 * (read-through). Writes invalidate the key.
 */
public final class CacheSpec extends ComponentSpec<CacheConfig> {

	/** The type name, which services' rules look for. */
	public static final String TYPE = "cache";

	/** The port a read-through cache sends its misses to. */
	public static final String MISS = "miss";

	private static final String MISS_DESCRIPTION = "Read-through: where the cache sends reads it does not hold"
			+ " (usually the database). Leave it empty for cache-aside, where the calling service reads the data"
			+ " store.";

	/**
	 * Creates the cache type: commands come in, and a read-through cache's misses go out.
	 */
	public CacheSpec() {
		super(new ComponentIdentity(TYPE, "Cache", Category.STORAGE), CacheConfig.class,
				List.of(Port.in("in", "Commands", "Reads (lookups) and writes (invalidations)."),
						Port.out(MISS, "Miss", MISS_DESCRIPTION).carrying(RequestType.READ).withMaxEdges(1)));
	}

	@Override
	public Map<String, TechnologyProfile> technologies() {
		return CacheConfig.PROFILES.byName();
	}

	/**
	 * Through the miss port, the reads that come in, when the cache can miss; nothing
	 * else leaves it.
	 */
	@Override
	public Set<RequestType> emittedTypes(String port, Set<RequestType> incoming, CacheConfig config, GraphView view) {
		if (port.equals(MISS) && config.canMiss() && incoming.contains(RequestType.READ)) {
			return EnumSet.of(RequestType.READ);
		}
		return EnumSet.noneOf(RequestType.class);
	}

	@Override
	public List<Issue> validate(NodeSpec node, CacheConfig config, GraphView view) {
		List<Issue> issues = new ArrayList<>();
		List<EdgeSpec> missEdges = view.outEdges(node.id())
			.stream()
			.filter((edge) -> edge.sourcePort().equals(MISS))
			.toList();
		unroutedMiss(node, config, view, missEdges).ifPresent(issues::add);
		unusedMiss(node, config, missEdges).ifPresent(issues::add);
		keyspaceBeyondMemory(node, config, view).ifPresent(issues::add);
		singleThreaded(node, config).ifPresent(issues::add);
		neverFills(node, config).ifPresent(issues::add);
		return issues;
	}

	private static Optional<Issue> unroutedMiss(NodeSpec node, CacheConfig config, GraphView view,
			List<EdgeSpec> missEdges) {
		if (!config.canMiss() || !missEdges.isEmpty() || !receivesReads(node, view)) {
			return Optional.empty();
		}
		List<String> unhandled = readersWithoutMissPath(node, view);
		if (unhandled.isEmpty()) {
			return Optional.empty();
		}
		String what = (config.hitModel() == HitModel.FIXED_RATIO)
				? "hit ratio " + PythonFormat.format(config.hitRatio(), ".0%") : "TTL-based keys expire";
		return Optional.of(Issue
			.error("cache.miss_unrouted",
					("%s can miss (%s) but nothing reads the data on a miss: its 'miss' port is not connected"
							+ " and %s has no link set to 'Reads only on a cache miss'.")
						.formatted(node.name(), what, view.name(unhandled.getFirst())))
			.atNode(node.id())
			.withHint("Cache-aside: give the service a link to the database with 'Reads only on a cache"
					+ " miss' on. Read-through: connect the cache's 'miss' port to the database."));
	}

	/**
	 * Whether reads reach the node; when the request types were not worked out, they are
	 * taken to.
	 * @param node the cache's node
	 * @param view the design the node is in
	 * @return {@code true} unless the node is known to receive no reads
	 */
	private static boolean receivesReads(NodeSpec node, GraphView view) {
		return view.incomingTypes().getOrDefault(node.id(), Set.of(RequestType.READ)).contains(RequestType.READ);
	}

	/**
	 * The callers that read from the cache but cannot read the data store themselves on a
	 * miss (cache-aside): those that are not services, or have no link set to read only
	 * on a miss.
	 * @param node the cache's node
	 * @param view the design the node is in
	 * @return the callers' ids, in the order of their links
	 */
	private static List<String> readersWithoutMissPath(NodeSpec node, GraphView view) {
		Set<String> readers = new LinkedHashSet<>();
		for (EdgeSpec edge : view.inEdges(node.id())) {
			if (edge.config().carries(RequestType.READ)) {
				readers.add(edge.source());
			}
		}
		return readers.stream().filter((source) -> !readsOnMiss(view, source) || !isService(view, source)).toList();
	}

	private static boolean readsOnMiss(GraphView view, String nodeId) {
		return view.outEdges(nodeId)
			.stream()
			.anyMatch((edge) -> edge.config().readsOnMiss() && edge.config().carries(RequestType.READ));
	}

	private static boolean isService(GraphView view, String nodeId) {
		return view.node(nodeId).map((node) -> node.type().equals(ServiceSpec.TYPE)).orElse(false);
	}

	private static Optional<Issue> unusedMiss(NodeSpec node, CacheConfig config, List<EdgeSpec> missEdges) {
		if (config.canMiss() || missEdges.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(Issue
			.warning("cache.miss_unused",
					"%s has a 100%% hit ratio, so its miss link is never used.".formatted(node.name()))
			.atNode(node.id())
			.atEdge(missEdges.getFirst().id()));
	}

	private static Optional<Issue> keyspaceBeyondMemory(NodeSpec node, CacheConfig config, GraphView view) {
		OptionalLong capacity = config.capacityKeys();
		Double memoryGib = config.memoryGib();
		if (capacity.isEmpty() || memoryGib == null || config.hitModel() != HitModel.FIXED_RATIO) {
			return Optional.empty();
		}
		long keys = view.workload().keyCount();
		if (keys <= capacity.getAsLong()) {
			return Optional.empty();
		}
		double need = keys * config.itemSizeKb() / CacheConfig.KIB_PER_GIB / CacheConfig.KEY_MEMORY_SHARE;
		return Optional.of(Issue
			.warning("cache.keyspace_exceeds_memory",
					("%s holds about %s keys in %s GiB but the workload uses %s; the %s hit ratio may"
							+ " be optimistic (all keys need about %s GiB).")
						.formatted(node.name(), PythonFormat.format(capacity.getAsLong(), ","),
								PythonFormat.format(memoryGib, "g"), PythonFormat.format(keys, ","),
								PythonFormat.format(config.hitRatio(), ".0%"), PythonFormat.format(need, ",.1f")))
			.atNode(node.id())
			.atPath("memoryGib")
			.withHint("Add memory, or use the TTL model to simulate eviction."));
	}

	private static Optional<Issue> singleThreaded(NodeSpec node, CacheConfig config) {
		if (config.concurrency() <= 1 || !config.runsOnOneThread()) {
			return Optional.empty();
		}
		return Optional.of(Issue
			.warning("cache.single_threaded",
					("%s is %s, which executes commands on one thread, so its %d command executors count"
							+ " as 1 per shard.")
						.formatted(node.name(), config.technology().json(), config.concurrency()))
			.atNode(node.id())
			.atPath("concurrency")
			.withHint("Add shards (cluster nodes) to run more commands in parallel."));
	}

	private static Optional<Issue> neverFills(NodeSpec node, CacheConfig config) {
		if (config.hitModel() != HitModel.TTL_KEYSPACE || config.populateOnMiss()) {
			return Optional.empty();
		}
		return Optional.of(
				Issue
					.warning("cache.never_fills",
							("%s uses the TTL model but does not populate on miss, so it never fills and every read"
									+ " misses.")
								.formatted(node.name()))
					.atNode(node.id())
					.atPath("populateOnMiss"));
	}

}
