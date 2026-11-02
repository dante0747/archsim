package io.archsim.model.component.cache;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.component.Port;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.validation.Issue;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/**
 * A cache. Reads that hit return from memory. On a miss either the calling service reads the data
 * store itself (cache-aside: the miss port stays empty and the service's link to the store reads
 * only on a miss), or the cache asks the store linked to its miss port (read-through). Writes
 * invalidate the key.
 */
public final class CacheSpec extends ComponentSpec<CacheConfig> {

    /** The type name, which services' rules look for. */
    public static final String TYPE = "cache";

    /** The port a read-through cache sends its misses to. */
    public static final String MISS = "miss";

    public CacheSpec() {
        super(
                TYPE,
                "Cache",
                Category.STORAGE,
                CacheConfig.class,
                List.of(
                        Port.in("in", "Commands", "Reads (lookups) and writes (invalidations)."),
                        Port.out(
                                        MISS,
                                        "Miss",
                                        "Read-through: where the cache sends reads it does not hold (usually the"
                                                + " database). Leave it empty for cache-aside, where the calling service"
                                                + " reads the data store.")
                                .carrying(RequestType.READ)
                                .withMaxEdges(1)));
    }

    /** Through the miss port, the reads that come in, when the cache can miss; nothing else leaves it. */
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
        String name = node.name();
        List<EdgeSpec> missEdges = view.outEdges(node.id()).stream()
                .filter(e -> e.sourcePort().equals(MISS))
                .toList();
        boolean receivesReads = view.incomingTypes()
                .getOrDefault(node.id(), Set.of(RequestType.READ))
                .contains(RequestType.READ);
        // cache-aside: callers that read the data store themselves on a miss
        Set<String> readers = new LinkedHashSet<>();
        for (EdgeSpec in : view.inEdges(node.id())) {
            if (in.config().carries(RequestType.READ)) {
                readers.add(in.source());
            }
        }
        List<String> unhandled = readers.stream()
                .filter(source -> view.outEdges(source).stream()
                                .noneMatch(x ->
                                        x.config().readsOnMiss() && x.config().carries(RequestType.READ))
                        || !view.node(source)
                                .map(n -> n.type().equals(ServiceSpec.TYPE))
                                .orElse(false))
                .toList();
        if (config.canMiss() && missEdges.isEmpty() && receivesReads && !unhandled.isEmpty()) {
            String what = config.hitModel() == HitModel.FIXED_RATIO
                    ? "hit ratio " + PythonFormat.format(config.hitRatio(), ".0%")
                    : "TTL-based keys expire";
            issues.add(Issue.error(
                            "cache.miss_unrouted",
                            ("%s can miss (%s) but nothing reads the data on a miss: its 'miss' port is not connected"
                                            + " and %s has no link set to 'Reads only on a cache miss'.")
                                    .formatted(name, what, view.name(unhandled.getFirst())))
                    .atNode(node.id())
                    .withHint("Cache-aside: give the service a link to the database with 'Reads only on a cache"
                            + " miss' on. Read-through: connect the cache's 'miss' port to the database."));
        }
        if (!config.canMiss() && !missEdges.isEmpty()) {
            issues.add(Issue.warning(
                            "cache.miss_unused",
                            "%s has a 100%% hit ratio, so its miss link is never used.".formatted(name))
                    .atNode(node.id())
                    .atEdge(missEdges.getFirst().id()));
        }
        OptionalLong capacity = config.capacityKeys();
        Double memoryGib = config.memoryGib();
        if (capacity.isPresent() && memoryGib != null && config.hitModel() == HitModel.FIXED_RATIO) {
            long keys = view.workload().keyCount();
            if (keys > capacity.getAsLong()) {
                double need = keys * config.itemSizeKb() / (1024 * 1024) / CacheConfig.KEY_MEMORY_SHARE;
                issues.add(Issue.warning(
                                "cache.keyspace_exceeds_memory",
                                ("%s holds about %s keys in %s GiB but the workload uses %s; the %s hit ratio may"
                                                + " be optimistic (all keys need about %s GiB).")
                                        .formatted(
                                                name,
                                                PythonFormat.format(capacity.getAsLong(), ","),
                                                PythonFormat.format(memoryGib, "g"),
                                                PythonFormat.format(keys, ","),
                                                PythonFormat.format(config.hitRatio(), ".0%"),
                                                PythonFormat.format(need, ",.1f")))
                        .atNode(node.id())
                        .atPath("memoryGib")
                        .withHint("Add memory, or use the TTL model to simulate eviction."));
            }
        }
        if (config.concurrency() > 1 && config.runsOnOneThread()) {
            issues.add(Issue.warning(
                            "cache.single_threaded",
                            ("%s is %s, which executes commands on one thread, so its %d command executors count"
                                            + " as 1 per shard.")
                                    .formatted(name, config.technology().json(), config.concurrency()))
                    .atNode(node.id())
                    .atPath("concurrency")
                    .withHint("Add shards (cluster nodes) to run more commands in parallel."));
        }
        if (config.hitModel() == HitModel.TTL_KEYSPACE && !config.populateOnMiss()) {
            issues.add(Issue.warning(
                            "cache.never_fills",
                            ("%s uses the TTL model but does not populate on miss, so it never fills and every read"
                                            + " misses.")
                                    .formatted(name))
                    .atNode(node.id())
                    .atPath("populateOnMiss"));
        }
        return issues;
    }
}
