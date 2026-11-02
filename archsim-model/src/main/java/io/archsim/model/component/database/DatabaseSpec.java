package io.archsim.model.component.database;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.component.Port;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.validation.Issue;
import java.util.ArrayList;
import java.util.List;

/**
 * A database: read and write queries on a limited number of connections per instance, behaving
 * like the technology picked (row locks and group commit, majority writes, capacity units,
 * fan-out searches, replicas that lag). It sends no requests.
 */
public final class DatabaseSpec extends ComponentSpec<DatabaseConfig> {

    public DatabaseSpec() {
        super(
                "database",
                "Database",
                Category.STORAGE,
                DatabaseConfig.class,
                List.of(Port.in("in", "Queries", "Read and write queries.")));
    }

    @Override
    public List<Issue> validate(NodeSpec node, DatabaseConfig config, GraphView view) {
        List<Issue> issues = new ArrayList<>();
        String name = node.name();
        if (config.consistency() != Consistency.ONE && config.replicationFactor() == 1) {
            issues.add(Issue.warning(
                            "database.consistency_unused",
                            ("%s uses consistency '%s' with a replication factor of 1, so the consistency level has"
                                            + " no effect.")
                                    .formatted(name, config.consistency().json()))
                    .atNode(node.id())
                    .atPath("consistency")
                    .withHint("Raise the replication factor (e.g. 3) or set consistency to 'one'."));
        }
        int parts = config.partitions();
        Double dataSizeGib = config.dataSizeGib();
        Double storageGib = config.storageGib();
        if (dataSizeGib != null && storageGib != null) {
            double perInstance = dataSizeGib / parts;
            if (perInstance > storageGib) {
                issues.add(Issue.error(
                                "database.storage_full",
                                "%s stores about %s GiB per instance (%s GiB over %d shard(s)) but has %s GiB of storage."
                                        .formatted(
                                                name,
                                                PythonFormat.format(perInstance, ",.0f"),
                                                PythonFormat.format(dataSizeGib, ",.0f"),
                                                parts,
                                                PythonFormat.format(storageGib, ",.0f")))
                        .atNode(node.id())
                        .atPath("storageGib")
                        .withHint("Add storage, add shards, or lower the data size."));
            } else if (perInstance > 0.85 * storageGib) {
                issues.add(Issue.warning(
                                "database.storage_nearly_full",
                                ("%s would use %s of its storage per instance; leave room for growth, indexes and"
                                                + " compaction.")
                                        .formatted(name, PythonFormat.format(perInstance / storageGib, ".0%")))
                        .atNode(node.id())
                        .atPath("storageGib"));
            }
        }
        if (config.workingSetGib() != null && config.memoryGib() == null) {
            issues.add(Issue.warning(
                            "database.working_set_without_memory",
                            "%s has a hot working set but no memory, so the buffer cache is not modelled."
                                    .formatted(name))
                    .atNode(node.id())
                    .atPath("memoryGib")
                    .withHint("Set the memory per instance."));
        }
        int keys = view.workload().keyCount();
        if (parts > keys) {
            issues.add(Issue.warning(
                            "database.shards_exceed_keys",
                            "%s has %d shards but the workload only touches %d distinct keys, so some shards stay empty."
                                    .formatted(name, parts, keys))
                    .atNode(node.id())
                    .atPath("shards"));
        }
        if (config.metered() && config.shards() != 1) {
            issues.add(Issue.warning(
                            "database.shards_from_capacity",
                            ("%s meters capacity units, so its partitions follow from capacity and size (%d); the"
                                            + " shards setting (%d) is ignored.")
                                    .formatted(name, parts, config.shards()))
                    .atNode(node.id())
                    .atPath("shards"));
        }
        Double cpuCores = config.cpuCores();
        if (config.technology() == DatabaseTechnology.POSTGRESQL
                && cpuCores != null
                && cpuCores != 0
                && config.maxConnections() > 4 * cpuCores) {
            issues.add(Issue.warning(
                            "database.too_many_connections",
                            ("%s runs up to %d queries at once on %s vCPU. PostgreSQL throughput usually peaks at a"
                                            + " few active connections per core; more mostly adds contention.")
                                    .formatted(name, config.maxConnections(), PythonFormat.format(cpuCores, "g")))
                    .atNode(node.id())
                    .atPath("maxConnections")
                    .withHint("Pool connections (e.g. PgBouncer) so active queries stay near 2-4 per core."));
        }
        if (config.effectiveReadFrom() == ReadFrom.REPLICAS && config.readReplicas() == 0) {
            issues.add(Issue.warning(
                            "database.no_replicas_to_read",
                            "%s reads from replicas but has none, so reads go to the primary.".formatted(name))
                    .atNode(node.id())
                    .atPath("readFrom"));
        }
        return issues;
    }
}
