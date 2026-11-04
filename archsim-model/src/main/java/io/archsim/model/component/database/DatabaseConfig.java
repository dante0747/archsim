package io.archsim.model.component.database;

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
import io.archsim.model.distribution.LogNormal;

/**
 * A database: its connections, query times, replicas and shards, the technology's
 * behaviour settings, capacity units, resources and data size.
 *
 * @param technology the database product
 * @param maxConnections queries one instance runs at once
 * @param readServiceTimeMs the time a read takes on a connection
 * @param writeServiceTimeMs the time a write takes on a connection
 * @param readReplicas read replicas per shard
 * @param shards partitions of the keyspace
 * @param replicationFactor copies of each key (for the consistency level)
 * @param consistency how many copies an operation waits for
 * @param throughputLimitRps operations per second it admits; {@code null}: no limit
 * @param queueCapacity queries that may wait for a connection per instance
 * @param queueTimeoutMs how long a query may wait for a connection; {@code null}: no
 * limit
 * @param rowLocks whether writes to a row wait for each other; auto follows the
 * technology
 * @param readsWaitForWrites whether reads wait for a row's writes; auto follows the
 * technology
 * @param commitFlush whether a write waits for the log flush; auto follows the technology
 * @param commitFlushMs the time a log flush takes
 * @param replication what a write waits for from replicas; auto follows the technology
 * @param replicaApplyWorkers threads a replica replays writes with; {@code null}: the
 * technology's
 * @param replicaApplyFactor a replayed write's time as a share of the write's
 * @param replicationDelayMs the time a write takes to reach a replica
 * @param readFrom which instances serve reads; auto follows the technology
 * @param readTickets reads that run at once; {@code null}: the technology's
 * @param writeTickets writes that run at once; {@code null}: the technology's
 * @param scatterReads the share of reads sent to every shard; {@code null}: the
 * technology's
 * @param routerOverheadMs the time a query router adds; {@code null}: the technology's
 * @param sstablesPerRead SSTables a cold read touches; {@code null}: the technology's
 * @param capacityUnits whether capacity is metered in units; auto follows the technology
 * @param capacityMode metered capacity bought per request or provisioned
 * @param readCapacityUnits provisioned read units
 * @param writeCapacityUnits provisioned write units
 * @param itemSizeKb the size of one item, for capacity units
 * @param consistentReads eventually or strongly consistent reads, for capacity units
 * @param cpuCores vCPU per instance; {@code null}: not modelled
 * @param cpuShare the share of a query's time that needs a CPU core
 * @param siblingSpeed a vCPU's speed while its core's other vCPU is busy too (schema 1.7)
 * @param memoryGib memory per instance; {@code null}: not modelled
 * @param workingSetGib the data that is read often; {@code null}: not modelled
 * @param diskReadMs the time a read from disk takes
 * @param storageGib disk per instance; {@code null}: not modelled
 * @param instanceType the instance type in the design's price snapshot (schema 1.4)
 * @param dataSizeGib the data stored, over all shards; {@code null}: not modelled
 * @param costPerHour the price of one instance per hour, in USD
 */
public record DatabaseConfig(
		@Default("postgresql") @Description(Descriptions.TECHNOLOGY) @Ui(
				group = Descriptions.TECHNOLOGY_GROUP) DatabaseTechnology technology,
		@Default("10") @Description(Descriptions.MAX_CONNECTIONS) @Ui(label = "Max connections per instance",
				group = Descriptions.CAPACITY_GROUP, headline = true) @Ge(1) @Le(100_000) int maxConnections,

		@DefaultFrom("DEFAULT_READ_SERVICE_TIME") @Description(Descriptions.READ_SERVICE_TIME_MS) @Ui(
				label = "Read query time", unit = "ms", group = Descriptions.PERFORMANCE_GROUP, assumption = true,
				headline = true) Distribution readServiceTimeMs,

		@DefaultFrom("DEFAULT_WRITE_SERVICE_TIME") @Description(Descriptions.WRITE_SERVICE_TIME_MS) @Ui(
				label = "Write query time", unit = "ms", group = Descriptions.PERFORMANCE_GROUP,
				assumption = true) Distribution writeServiceTimeMs,

		@Default("0") @Description(Descriptions.READ_REPLICAS) @Ui(group = Descriptions.TOPOLOGY_GROUP,
				headline = true) @Ge(0) @Le(15) int readReplicas,
		@Default("1") @Description(Descriptions.SHARDS) @Ui(label = "Shards (partitions)",
				group = Descriptions.TOPOLOGY_GROUP) @Ge(1) @Le(256) int shards,
		@Default("1") @Description(Descriptions.REPLICATION_FACTOR) @Ui(
				group = Descriptions.TOPOLOGY_GROUP) @Ge(1) @Le(7) int replicationFactor,
		@Default("one") @Description(Descriptions.CONSISTENCY) @Ui(label = "Consistency level",
				group = Descriptions.TOPOLOGY_GROUP) Consistency consistency,
		@Description(Descriptions.THROUGHPUT_LIMIT_RPS) @Ui(label = "Throughput limit per shard", unit = "ops/s",
				group = Descriptions.CAPACITY_GROUP,
				assumption = true) @Gt(0) @Le(1_000_000) @Nullable Double throughputLimitRps,
		@Default("200") @Description(Descriptions.QUEUE_CAPACITY) @Ui(label = "Wait queue limit",
				group = Descriptions.CAPACITY_GROUP) @Ge(0) @Le(100_000) int queueCapacity,
		@Default("1000.0") @Description(Descriptions.QUEUE_TIMEOUT_MS) @Ui(label = "Acquire timeout", unit = "ms",
				group = Descriptions.CAPACITY_GROUP) @Gt(0) @Le(600_000) @Nullable Double queueTimeoutMs,
		@Default("auto") @Description(Descriptions.ROW_LOCKS) @Ui(label = "Row locks on writes",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) Toggle rowLocks,
		@Default("auto") @Description(Descriptions.READS_WAIT_FOR_WRITES) @Ui(label = "Reads wait for writers",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) Toggle readsWaitForWrites,
		@Default("auto") @Description(Descriptions.COMMIT_FLUSH) @Ui(label = "Commits wait for the log flush",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) Toggle commitFlush,

		@DefaultFrom("DEFAULT_COMMIT_FLUSH") @Description(Descriptions.COMMIT_FLUSH_MS) @Ui(label = "Log flush time",
				unit = "ms", group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP,
				assumption = true) Distribution commitFlushMs,

		@Default("auto") @Description(Descriptions.REPLICATION) @Ui(label = "Replica writes",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) Replication replication,
		@Description(Descriptions.REPLICA_APPLY_WORKERS) @Ui(
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) @Ge(1) @Le(256) @Nullable Integer replicaApplyWorkers,
		@Default("0.5") @Description(Descriptions.REPLICA_APPLY_FACTOR) @Ui(label = "Replay cost vs. write time",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP, assumption = true,
				step = 0.05) @Gt(0) @Le(5) double replicaApplyFactor,

		@DefaultFrom("DEFAULT_REPLICATION_DELAY") @Description(Descriptions.REPLICATION_DELAY_MS) @Ui(
				label = "Replication network delay", unit = "ms", group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP,
				assumption = true) Distribution replicationDelayMs,

		@Default("auto") @Description(Descriptions.READ_FROM) @Ui(label = "Reads served by",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) ReadFrom readFrom,
		@Description(Descriptions.READ_TICKETS) @Ui(label = "Concurrent reads (tickets)",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) @Ge(1) @Le(100_000) @Nullable Integer readTickets,
		@Description(Descriptions.WRITE_TICKETS) @Ui(label = "Concurrent writes (tickets)",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) @Ge(1) @Le(100_000) @Nullable Integer writeTickets,
		@Description(Descriptions.SCATTER_READS) @Ui(label = "Reads sent to every shard",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP,
				step = 0.05) @Ge(0) @Le(1) @Nullable Double scatterReads,
		@Description(Descriptions.ROUTER_OVERHEAD_MS) @Ui(label = "Router / coordinator time", unit = "ms",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) @Ge(0) @Le(1000) @Nullable Double routerOverheadMs,
		@Description(Descriptions.SSTABLES_PER_READ) @Ui(label = "SSTables per cold read",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP,
				assumption = true) @Ge(1) @Le(50) @Nullable Double sstablesPerRead,
		@Default("auto") @Description(Descriptions.CAPACITY_UNITS) @Ui(label = "Capacity units and partition limits",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) Toggle capacityUnits,
		@Default("on_demand") @Description(Descriptions.CAPACITY_MODE) @Ui(
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP,
				visibleWhen = "technology=dynamodb") CapacityMode capacityMode,
		@Default("1000") @Description(Descriptions.READ_CAPACITY_UNITS) @Ui(unit = "RCU",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP,
				visibleWhen = "technology=dynamodb") @Gt(0) @Le(10_000_000) double readCapacityUnits,
		@Default("500") @Description(Descriptions.WRITE_CAPACITY_UNITS) @Ui(unit = "WCU",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP,
				visibleWhen = "technology=dynamodb") @Gt(0) @Le(10_000_000) double writeCapacityUnits,
		@Default("1.0") @Description(Descriptions.ITEM_SIZE_KB) @Ui(label = "Item size", unit = "KB",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP, assumption = true,
				visibleWhen = "technology=dynamodb") @Gt(0) @Le(400) double itemSizeKb,
		@Default("eventual") @Description(Descriptions.CONSISTENT_READS) @Ui(label = "Read consistency",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP,
				visibleWhen = "technology=dynamodb") ConsistentReads consistentReads,
		@Description(Descriptions.CPU_CORES) @Ui(label = "CPU", unit = "vCPU", group = Descriptions.RESOURCES_GROUP,
				step = 0.5) @Gt(0) @Le(Resources.MAX_CPU_CORES) @Nullable Double cpuCores,
		@Default("0.5") @Description(Descriptions.CPU_SHARE) @Ui(label = "CPU share of query time",
				group = Descriptions.RESOURCES_GROUP, assumption = true, step = 0.05) @Ge(0) @Le(1) double cpuShare,
		@Description(Resources.SIBLING_SPEED) @Ui(label = "Hyperthread sibling speed",
				group = Descriptions.RESOURCES_GROUP,
				step = 0.05) @OmitWhenDefault @Gt(0) @Le(1) @Nullable Double siblingSpeed,
		@Description(Descriptions.MEMORY_GIB) @Ui(label = "Memory", unit = "GiB", group = Descriptions.RESOURCES_GROUP,
				step = 0.5) @Gt(0) @Le(Resources.MAX_MEMORY_GIB) @Nullable Double memoryGib,
		@Description(Descriptions.WORKING_SET_GIB) @Ui(label = "Hot working set", unit = "GiB",
				group = Descriptions.RESOURCES_GROUP,
				assumption = true) @Gt(0) @Le(10_000_000) @Nullable Double workingSetGib,

		@DefaultFrom("DEFAULT_DISK_READ") @Description(Descriptions.DISK_READ_MS) @Ui(label = "Disk read time",
				unit = "ms", group = Descriptions.RESOURCES_GROUP, assumption = true) Distribution diskReadMs,

		@Description(Descriptions.STORAGE_GIB) @Ui(label = "Storage", unit = "GiB",
				group = Descriptions.RESOURCES_GROUP) @Gt(0) @Le(Resources.MAX_STORAGE_GIB) @Nullable Double storageGib,
		@Description(Descriptions.INSTANCE_TYPE) @Ui(
				group = Descriptions.RESOURCES_GROUP) @Matches(Resources.INSTANCE_TYPE) @Nullable String instanceType,
		@Description(Descriptions.DATA_SIZE_GIB) @Ui(label = "Data size", unit = "GiB",
				group = Descriptions.RESOURCES_GROUP,
				assumption = true) @Gt(0) @Le(100_000_000) @Nullable Double dataSizeGib,
		@Default("0.35") @Description(Descriptions.COST_PER_HOUR) @Ui(label = "Cost per instance-hour", unit = "$/h",
				group = Descriptions.COST_GROUP, assumption = true) @Ge(0) @Le(100_000) double costPerHour) {

	/** Read units one partition of a metered table serves per second. */
	public static final double PARTITION_READ_UNITS = 3000.0;

	/** Write units one partition of a metered table serves per second. */
	public static final double PARTITION_WRITE_UNITS = 1000.0;

	/** The data one partition of a metered table holds, in GiB. */
	public static final double PARTITION_GIB = 10.0;

	/** The read units an on-demand table is sized for at first. */
	public static final double ON_DEMAND_READ_UNITS = 12_000.0;

	/** The write units an on-demand table is sized for at first. */
	public static final double ON_DEMAND_WRITE_UNITS = 4000.0;

	/** The default time of a read on a connection: 5 ms median, 20 ms p95. */
	static final Distribution DEFAULT_READ_SERVICE_TIME = new LogNormal(5.0, 20.0);

	/** The default time of a write on a connection: 10 ms median, 40 ms p95. */
	static final Distribution DEFAULT_WRITE_SERVICE_TIME = new LogNormal(10.0, 40.0);

	/** The default time of a log flush: 1 ms median, 3 ms p95. */
	static final Distribution DEFAULT_COMMIT_FLUSH = new LogNormal(1.0, 3.0);

	/** The default time a write takes to reach a replica: 0.5 ms median, 2 ms p95. */
	static final Distribution DEFAULT_REPLICATION_DELAY = new LogNormal(0.5, 2.0);

	/** The default time of a read from disk: 1 ms median, 4 ms p95. */
	static final Distribution DEFAULT_DISK_READ = new LogNormal(1.0, 4.0);

	/**
	 * What each technology does by default (technology.py's {@code DATABASE_PROFILES}).
	 */
	static final TechnologyProfiles<DatabaseTechnology> PROFILES = TechnologyProfiles.builder(DatabaseTechnology.class)
		.with(DatabaseTechnology.POSTGRESQL, relational(TechnologyProfile.core(), "off", 1))
		.with(DatabaseTechnology.MYSQL, relational(TechnologyProfile.experimental(), "off", 4))
		.with(DatabaseTechnology.MARIADB, relational(TechnologyProfile.experimental(), "off", 4))
		.with(DatabaseTechnology.ORACLE, relational(TechnologyProfile.experimental(), "off", 4))
		.with(DatabaseTechnology.SQLSERVER, relational(TechnologyProfile.experimental(), "on", 4))
		.with(DatabaseTechnology.COCKROACHDB, relational(TechnologyProfile.experimental(), "off", 4))
		.with(DatabaseTechnology.YUGABYTEDB, relational(TechnologyProfile.experimental(), "off", 4))
		.with(DatabaseTechnology.MONGODB,
				TechnologyProfile.experimental()
					.with("rowLocks", "on")
					.with("readsWaitForWrites", "off")
					.with("commitFlush", "on")
					.with("replication", "majority")
					.with("replicaApplyWorkers", 16)
					.with("readFrom", "primary")
					.with("readTickets", 128)
					.with("writeTickets", 128)
					.with("routerOverheadMs", 0.5))
		.with(DatabaseTechnology.COUCHBASE,
				TechnologyProfile.experimental()
					.with("rowLocks", "off")
					.with("commitFlush", "off")
					.with("replication", "async")
					.with("replicaApplyWorkers", 4)
					.with("readFrom", "primary"))
		.with(DatabaseTechnology.CASSANDRA, wideColumn(2.0))
		.with(DatabaseTechnology.SCYLLADB, wideColumn(1.5))
		.with(DatabaseTechnology.DYNAMODB,
				TechnologyProfile.experimental()
					.with("rowLocks", "off")
					.with("commitFlush", "off")
					.with("replication", "none")
					.with("readFrom", "all")
					.with("capacityUnits", "on"))
		.with(DatabaseTechnology.ELASTICSEARCH, search())
		.with(DatabaseTechnology.OPENSEARCH, search())
		.with(DatabaseTechnology.GENERIC,
				TechnologyProfile.core()
					.with("rowLocks", "off")
					.with("readsWaitForWrites", "off")
					.with("commitFlush", "off")
					.with("replication", "none")
					.with("readFrom", "all"))
		.build();

	/** The threads a search engine's replica replays writes with. */
	private static final int SEARCH_APPLY_WORKERS = 4;

	/**
	 * Taken off before rounding up, so floating-point error does not add a partition.
	 */
	private static final double ROUNDING_TOLERANCE = 1e-9;

	/** A read capacity unit covers this much of an item. */
	private static final double READ_UNIT_KB = 4.0;

	/** An eventually consistent read takes half a unit. */
	private static final double EVENTUAL_READ_SHARE = 0.5;

	private static TechnologyProfile relational(TechnologyProfile tier, String readsWaitForWrites, int applyWorkers) {
		return tier.with("rowLocks", "on")
			.with("readsWaitForWrites", readsWaitForWrites)
			.with("commitFlush", "on")
			.with("replication", "async")
			.with("replicaApplyWorkers", applyWorkers)
			.with("readFrom", "all");
	}

	private static TechnologyProfile wideColumn(double sstablesPerRead) {
		return TechnologyProfile.experimental()
			.with("rowLocks", "off")
			.with("commitFlush", "off")
			.with("replication", "none")
			.with("readFrom", "all")
			.with("sstablesPerRead", sstablesPerRead);
	}

	private static TechnologyProfile search() {
		return TechnologyProfile.experimental()
			.with("rowLocks", "off")
			.with("commitFlush", "off")
			.with("replication", "all")
			.with("replicaApplyWorkers", SEARCH_APPLY_WORKERS)
			.with("readFrom", "all")
			.with("scatterReads", 1.0)
			// No setting has this name (the setting is routerOverheadMs). It is kept
			// because the Python engine has it, so the catalog's defaults stay the same.
			.with("coordinatorMs", 1.0);
	}

	public TechnologyProfile profile() {
		return PROFILES.of(this.technology);
	}

	/**
	 * Whether capacity is metered in units (DynamoDB-style), after the technology's
	 * default.
	 * @return {@code true} when capacity units limit the throughput
	 */
	public boolean metered() {
		return profile().choice("capacityUnits", this.capacityUnits, Toggle.AUTO, Toggle.OFF) == Toggle.ON;
	}

	/**
	 * Whether a write waits for the log flush, after the technology's default.
	 * @return {@code true} when a commit flushes the log
	 */
	public boolean flushesCommits() {
		return profile().choice("commitFlush", this.commitFlush, Toggle.AUTO, Toggle.OFF) == Toggle.ON;
	}

	/**
	 * The read capacity units per second a metered table has: as provisioned, or on
	 * demand.
	 * @return the read units per second
	 */
	public double readUnitsPerSecond() {
		return (this.capacityMode == CapacityMode.PROVISIONED) ? this.readCapacityUnits : ON_DEMAND_READ_UNITS;
	}

	/**
	 * The write capacity units per second a metered table has: as provisioned, or on
	 * demand.
	 * @return the write units per second
	 */
	public double writeUnitsPerSecond() {
		return (this.capacityMode == CapacityMode.PROVISIONED) ? this.writeCapacityUnits : ON_DEMAND_WRITE_UNITS;
	}

	/**
	 * The read capacity units one read takes: a unit per 4 KB of the item, half for an
	 * eventually consistent read.
	 * @return the units per read
	 */
	public double unitsPerRead() {
		int blocks = (int) Math.ceil(this.itemSizeKb / READ_UNIT_KB - ROUNDING_TOLERANCE);
		return Math.max(1, blocks) * ((this.consistentReads == ConsistentReads.STRONG) ? 1.0 : EVENTUAL_READ_SHARE);
	}

	/**
	 * The write capacity units one write takes: a unit per KB of the item.
	 * @return the units per write
	 */
	public double unitsPerWrite() {
		return Math.max(1, (int) Math.ceil(this.itemSizeKb - ROUNDING_TOLERANCE));
	}

	/**
	 * Which instances serve reads, after the technology's default.
	 * @return the instances that serve reads; {@link ReadFrom#AUTO} when the technology
	 * has no default
	 */
	public ReadFrom effectiveReadFrom() {
		return profile().choice("readFrom", this.readFrom, ReadFrom.AUTO, ReadFrom.AUTO);
	}

	/**
	 * The shards, or for a metered table the partitions its capacity and size need.
	 * @return the partitions of the keyspace, at least one
	 */
	public int partitions() {
		if (!metered()) {
			return this.shards;
		}
		double partitionsNeeded = readUnitsPerSecond() / PARTITION_READ_UNITS
				+ writeUnitsPerSecond() / PARTITION_WRITE_UNITS;
		int byCapacity = (int) Math.ceil(partitionsNeeded - ROUNDING_TOLERANCE);
		return Math.max(1, Math.max(byCapacity, partitionsBySize()));
	}

	private int partitionsBySize() {
		if (this.dataSizeGib == null || this.dataSizeGib == 0) {
			return 1;
		}
		return (int) Math.ceil(this.dataSizeGib / PARTITION_GIB);
	}

}
