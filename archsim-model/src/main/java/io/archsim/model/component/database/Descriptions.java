package io.archsim.model.component.database;

import io.archsim.model.component.Resources;

/**
 * What each setting of a database means, as the editor's inspector and the component
 * catalog show it.
 */
final class Descriptions {

	static final String CAPACITY_GROUP = "Capacity";

	static final String COST_GROUP = "Cost";

	static final String PERFORMANCE_GROUP = "Performance";

	static final String RESOURCES_GROUP = "Resources";

	static final String TECHNOLOGY_BEHAVIOUR_GROUP = "Technology behaviour";

	static final String TECHNOLOGY_GROUP = "Technology";

	static final String TOPOLOGY_GROUP = "Topology";

	static final String TECHNOLOGY = "The database product. It sets how the database behaves (locks, commit, "
			+ "replication, read routing, partition limits...), shown under Technology behaviour, where every "
			+ "setting can be overridden. Presets also fill in typical numbers.";

	static final String MAX_CONNECTIONS = "Queries executing concurrently on one instance (connection pool size / "
			+ "backend workers). Every primary and replica in every shard has its own limit.";

	static final String READ_SERVICE_TIME_MS = "Execution time of a read query on one instance (ms).";

	static final String WRITE_SERVICE_TIME_MS = "Execution time of a write on one instance, before the log flush and "
			+ "replica acknowledgements (ms).";

	static final String READ_REPLICAS = "Copies per shard that replay the primary's writes (replicas, secondaries). "
			+ "Writes always go to the primary; which instances serve reads depends on 'Reads served by'.";

	static final String SHARDS = "Partitions of the data, each with its own primary (and replicas). Requests are "
			+ "routed by hashing the key, so popular keys concentrate load on one shard. DynamoDB-style capacity "
			+ "units derive the partition count instead.";

	static final String REPLICATION_FACTOR = "Copies of each item kept by a leaderless or consensus store (e.g. "
			+ "Cassandra, distributed SQL). Combined with the consistency level it decides how many replica "
			+ "responses an operation waits for.";

	static final String CONSISTENCY = "Replica responses an operation waits for when the replication factor is above "
			+ "1: one, a majority (quorum) or all. Latency becomes the k-th fastest of the replicas.";

	static final String THROUGHPUT_LIMIT_RPS = "Provisioned throughput per shard (operations/s, bursts up to one "
			+ "second's worth). Requests above it are throttled. Empty = no limit.";

	static final String QUEUE_CAPACITY = "Queries that may wait for a connection on one instance; more are rejected.";

	static final String QUEUE_TIMEOUT_MS = "Connection acquire timeout (ms). Queries waiting longer are rejected. "
			+ "Empty = no limit.";

	static final String ROW_LOCKS = "Writes to the same key (row or document) take a lock: a second write waits until "
			+ "the first commits, holding its connection meanwhile. Auto: on for relational databases and "
			+ "MongoDB, off for Cassandra, DynamoDB and search engines.";

	static final String READS_WAIT_FOR_WRITES = "A read of a key that is being written waits for the writer's lock "
			+ "(SQL Server's default READ COMMITTED). Auto: on for SQL Server; MVCC databases read the last "
			+ "committed version instead.";

	static final String COMMIT_FLUSH = "Each write waits for the write-ahead log (or journal) to reach disk. Commits "
			+ "that arrive during a flush share the next one (group commit). Auto: on for relational databases "
			+ "and MongoDB.";

	static final String COMMIT_FLUSH_MS = "Time of one log flush (fsync) on the storage (ms). Network block storage "
			+ "is slower than local NVMe.";

	static final String REPLICATION = "How writes reach the replicas. async: the primary commits, replicas replay "
			+ "later (replication lag, stale reads). majority: the write also waits until a majority of the "
			+ "copies have it (MongoDB's default write concern). all: it waits for every replica (Elasticsearch). "
			+ "none: replicas only serve reads.";

	static final String REPLICA_APPLY_WORKERS = "Workers a replica uses to replay writes. Empty = the technology's "
			+ "default (PostgreSQL 1, MySQL 4, MongoDB 16).";

	static final String REPLICA_APPLY_FACTOR = "Replaying a write on a replica costs this share of the write's "
			+ "execution time.";

	static final String REPLICATION_DELAY_MS = "Network delay shipping a write (and an acknowledgement) between "
			+ "primary and replicas (ms).";

	static final String READ_FROM = "Which instances serve reads: all (round-robin over primary and replicas), the "
			+ "primary only (MongoDB's default read preference) or the replicas only (secondaryPreferred). Auto "
			+ "follows the technology.";

	static final String READ_TICKETS = "Read operations one instance runs at once, beyond the connection limit "
			+ "(MongoDB WiredTiger: 128). Empty = the technology's default (none for most).";

	static final String WRITE_TICKETS = "Write operations one instance runs at once (MongoDB WiredTiger: 128). Empty "
			+ "= technology default.";

	static final String SCATTER_READS = "Share of reads that do not target one shard and go to every shard, waiting "
			+ "for the slowest (searches; MongoDB queries without the shard key). Empty = technology default "
			+ "(Elasticsearch: every search).";

	static final String ROUTER_OVERHEAD_MS = "Routing or merging time added per operation when sharded or scattering "
			+ "(mongos, Elasticsearch coordinating node) (ms). Empty = technology default.";

	static final String SSTABLES_PER_READ = "Log-structured stores (Cassandra, Scylla): disk reads a buffer-cache "
			+ "miss costs, because a row can be spread over several SSTables. Empty = technology default.";

	static final String CAPACITY_UNITS = "Meter reads and writes in capacity units with per-partition limits (3,000 "
			+ "read and 1,000 write units per second each), DynamoDB-style. Auto: on for DynamoDB.";

	static final String CAPACITY_MODE = "On-demand: the table serves up to 12,000 read and 4,000 write units per "
			+ "second from the start. Provisioned: the units below, plus up to 300 s of banked burst.";

	static final String READ_CAPACITY_UNITS = "Provisioned read capacity units per second for the table.";

	static final String WRITE_CAPACITY_UNITS = "Provisioned write capacity units per second for the table.";

	static final String ITEM_SIZE_KB = "Average item size (KB): a read costs one unit per 4 KB, a write one per 1 KB.";

	static final String CONSISTENT_READS = "Eventually consistent reads cost half a unit (the default); strongly "
			+ "consistent reads a full unit.";

	static final String CPU_CORES = Resources.CPU_PER + "instance" + Resources.NOT_MODELLED_WHEN_EMPTY
			+ Resources.CPU_CONTENTION + " Only the CPU share of each query needs a core.";

	static final String CPU_SHARE = "Share of query time that needs a CPU core; the rest is I/O, locks and network. "
			+ "Only used when CPU is set.";

	static final String MEMORY_GIB = Resources.MEMORY_PER + "instance" + Resources.IN_GIB
			+ Resources.NOT_MODELLED_WHEN_EMPTY
			+ "With a working set, 75% of it is buffer cache: reads of data that does not fit go to disk.";

	static final String WORKING_SET_GIB = "Size of the data that is read often (GiB), across all shards. With memory "
			+ "set, the share of reads served from memory is min(1, 75% of memory / working set per shard).";

	static final String DISK_READ_MS = "Extra time of a read that misses the buffer cache and goes to disk (ms).";

	static final String STORAGE_GIB = Resources.STORAGE_PER + "instance" + Resources.IN_GIB
			+ Resources.NOT_MODELLED_WHEN_EMPTY
			+ "Checked against the data size: each instance stores its shard's data.";

	static final String INSTANCE_TYPE = Resources.INSTANCE_TYPE_OF_EACH + "instance" + Resources.INSTANCE_TYPE_USE;

	static final String DATA_SIZE_GIB = "Total data stored (GiB), across all shards. Used to check storage (and "
			+ "DynamoDB partitions).";

	static final String COST_PER_HOUR = "Price of one instance (primary or replica, per shard) per hour, used for "
			+ "cost estimates only. Illustrative default; use your own price.";

	private Descriptions() {
	}

}
