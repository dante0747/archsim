package io.archsim.model.component.database;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentIdentity;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.component.Port;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.issue.Issue;
import io.archsim.model.text.PythonFormat;

/**
 * A database: read and write queries on a limited number of connections per instance,
 * behaving like the technology picked (row locks and group commit, majority writes,
 * capacity units, fan-out searches, replicas that lag). It sends no requests.
 */
public final class DatabaseSpec extends ComponentSpec<DatabaseConfig> {

	/** The share of its storage above which an instance is nearly full. */
	private static final double NEARLY_FULL_SHARE = 0.85;

	/** Active connections per core beyond which PostgreSQL mostly adds contention. */
	private static final int CONNECTIONS_PER_CORE = 4;

	/** Creates the database type: queries come in, and nothing goes out. */
	public DatabaseSpec() {
		super(new ComponentIdentity("database", "Database", Category.STORAGE), DatabaseConfig.class,
				List.of(Port.in("in", "Queries", "Read and write queries.")));
	}

	@Override
	public List<Issue> validate(NodeSpec node, DatabaseConfig config, GraphView view) {
		List<Issue> issues = new ArrayList<>();
		int partitions = config.partitions();
		consistencyUnused(node, config).ifPresent(issues::add);
		storage(node, config, partitions).ifPresent(issues::add);
		workingSetWithoutMemory(node, config).ifPresent(issues::add);
		shardsBeyondKeys(node, partitions, view.workload().keyCount()).ifPresent(issues::add);
		shardsFromCapacity(node, config, partitions).ifPresent(issues::add);
		tooManyConnections(node, config).ifPresent(issues::add);
		noReplicasToRead(node, config).ifPresent(issues::add);
		return issues;
	}

	private static Optional<Issue> consistencyUnused(NodeSpec node, DatabaseConfig config) {
		if (config.consistency() == Consistency.ONE || config.replicationFactor() != 1) {
			return Optional.empty();
		}
		return Optional.of(Issue
			.warning("database.consistency_unused",
					("%s uses consistency '%s' with a replication factor of 1, so the consistency level has"
							+ " no effect.")
						.formatted(node.name(), config.consistency().json()))
			.atNode(node.id())
			.atPath("consistency")
			.withHint("Raise the replication factor (e.g. 3) or set consistency to 'one'."));
	}

	/**
	 * Whether each instance's share of the data fits in its storage.
	 * @param node the database's node
	 * @param config the database's config
	 * @param partitions the partitions the data is spread over
	 * @return an error when the data does not fit, a warning when it nearly fills the
	 * storage
	 */
	private static Optional<Issue> storage(NodeSpec node, DatabaseConfig config, int partitions) {
		Double dataSizeGib = config.dataSizeGib();
		Double storageGib = config.storageGib();
		if (dataSizeGib == null || storageGib == null) {
			return Optional.empty();
		}
		double perInstance = dataSizeGib / partitions;
		if (perInstance > storageGib) {
			return Optional.of(Issue
				.error("database.storage_full",
						"%s stores about %s GiB per instance (%s GiB over %d shard(s)) but has %s GiB of storage."
							.formatted(node.name(), PythonFormat.format(perInstance, ",.0f"),
									PythonFormat.format(dataSizeGib, ",.0f"), partitions,
									PythonFormat.format(storageGib, ",.0f")))
				.atNode(node.id())
				.atPath("storageGib")
				.withHint("Add storage, add shards, or lower the data size."));
		}
		if (perInstance > NEARLY_FULL_SHARE * storageGib) {
			return Optional.of(Issue
				.warning("database.storage_nearly_full",
						("%s would use %s of its storage per instance; leave room for growth, indexes and"
								+ " compaction.")
							.formatted(node.name(), PythonFormat.format(perInstance / storageGib, ".0%")))
				.atNode(node.id())
				.atPath("storageGib"));
		}
		return Optional.empty();
	}

	private static Optional<Issue> workingSetWithoutMemory(NodeSpec node, DatabaseConfig config) {
		if (config.workingSetGib() == null || config.memoryGib() != null) {
			return Optional.empty();
		}
		return Optional.of(Issue
			.warning("database.working_set_without_memory",
					"%s has a hot working set but no memory, so the buffer cache is not modelled."
						.formatted(node.name()))
			.atNode(node.id())
			.atPath("memoryGib")
			.withHint("Set the memory per instance."));
	}

	private static Optional<Issue> shardsBeyondKeys(NodeSpec node, int partitions, int keys) {
		if (partitions <= keys) {
			return Optional.empty();
		}
		return Optional.of(Issue
			.warning("database.shards_exceed_keys",
					"%s has %d shards but the workload only touches %d distinct keys, so some shards stay empty."
						.formatted(node.name(), partitions, keys))
			.atNode(node.id())
			.atPath("shards"));
	}

	private static Optional<Issue> shardsFromCapacity(NodeSpec node, DatabaseConfig config, int partitions) {
		if (!config.metered() || config.shards() == 1) {
			return Optional.empty();
		}
		return Optional.of(Issue
			.warning("database.shards_from_capacity",
					("%s meters capacity units, so its partitions follow from capacity and size (%d); the"
							+ " shards setting (%d) is ignored.")
						.formatted(node.name(), partitions, config.shards()))
			.atNode(node.id())
			.atPath("shards"));
	}

	private static Optional<Issue> tooManyConnections(NodeSpec node, DatabaseConfig config) {
		Double cpuCores = config.cpuCores();
		if (config.technology() != DatabaseTechnology.POSTGRESQL || cpuCores == null || cpuCores == 0
				|| config.maxConnections() <= CONNECTIONS_PER_CORE * cpuCores) {
			return Optional.empty();
		}
		return Optional.of(Issue
			.warning("database.too_many_connections",
					("%s runs up to %d queries at once on %s vCPU. PostgreSQL throughput usually peaks at a"
							+ " few active connections per core; more mostly adds contention.")
						.formatted(node.name(), config.maxConnections(), PythonFormat.format(cpuCores, "g")))
			.atNode(node.id())
			.atPath("maxConnections")
			.withHint("Pool connections (e.g. PgBouncer) so active queries stay near 2-4 per core."));
	}

	private static Optional<Issue> noReplicasToRead(NodeSpec node, DatabaseConfig config) {
		if (config.effectiveReadFrom() != ReadFrom.REPLICAS || config.readReplicas() != 0) {
			return Optional.empty();
		}
		return Optional.of(Issue
			.warning("database.no_replicas_to_read",
					"%s reads from replicas but has none, so reads go to the primary.".formatted(node.name()))
			.atNode(node.id())
			.atPath("readFrom"));
	}

}
