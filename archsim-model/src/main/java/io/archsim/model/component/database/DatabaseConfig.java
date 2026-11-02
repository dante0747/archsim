package io.archsim.model.component.database;

import static io.archsim.model.component.Resources.INSTANCE_TYPE;
import static io.archsim.model.component.Resources.MAX_CPU_CORES;
import static io.archsim.model.component.Resources.MAX_MEMORY_GIB;
import static io.archsim.model.component.Resources.MAX_STORAGE_GIB;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.OmitWhenDefault;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.component.TechnologyProfiles;
import io.archsim.model.component.Toggle;
import io.archsim.model.distribution.Distribution;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A database: its connections, query times, replicas and shards, the technology's behaviour
 * settings, capacity units, resources and data size.
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
 * @param queueTimeoutMs how long a query may wait for a connection; {@code null}: no limit
 * @param rowLocks whether writes to a row wait for each other; auto follows the technology
 * @param readsWaitForWrites whether reads wait for a row's writes; auto follows the technology
 * @param commitFlush whether a write waits for the log flush; auto follows the technology
 * @param commitFlushMs the time a log flush takes
 * @param replication what a write waits for from replicas; auto follows the technology
 * @param replicaApplyWorkers threads a replica replays writes with; {@code null}: the technology's
 * @param replicaApplyFactor a replayed write's time as a share of the write's
 * @param replicationDelayMs the time a write takes to reach a replica
 * @param readFrom which instances serve reads; auto follows the technology
 * @param readTickets reads that run at once; {@code null}: the technology's
 * @param writeTickets writes that run at once; {@code null}: the technology's
 * @param scatterReads the share of reads sent to every shard; {@code null}: the technology's
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
        @Default("postgresql") DatabaseTechnology technology,
        @Default("10") @Ge(1) @Le(100_000) int maxConnections,

        @Default("{\"kind\": \"lognormal\", \"median\": 5.0, \"p95\": 20.0}")
        Distribution readServiceTimeMs,

        @Default("{\"kind\": \"lognormal\", \"median\": 10.0, \"p95\": 40.0}")
        Distribution writeServiceTimeMs,

        @Default("0") @Ge(0) @Le(15) int readReplicas,
        @Default("1") @Ge(1) @Le(256) int shards,
        @Default("1") @Ge(1) @Le(7) int replicationFactor,
        @Default("one") Consistency consistency,
        @Gt(0) @Le(1_000_000) @Nullable Double throughputLimitRps,
        @Default("200") @Ge(0) @Le(100_000) int queueCapacity,
        @Default("1000.0") @Gt(0) @Le(600_000) @Nullable Double queueTimeoutMs,
        @Default("auto") Toggle rowLocks,
        @Default("auto") Toggle readsWaitForWrites,
        @Default("auto") Toggle commitFlush,

        @Default("{\"kind\": \"lognormal\", \"median\": 1.0, \"p95\": 3.0}")
        Distribution commitFlushMs,

        @Default("auto") Replication replication,
        @Ge(1) @Le(256) @Nullable Integer replicaApplyWorkers,
        @Default("0.5") @Gt(0) @Le(5) double replicaApplyFactor,

        @Default("{\"kind\": \"lognormal\", \"median\": 0.5, \"p95\": 2.0}")
        Distribution replicationDelayMs,

        @Default("auto") ReadFrom readFrom,
        @Ge(1) @Le(100_000) @Nullable Integer readTickets,
        @Ge(1) @Le(100_000) @Nullable Integer writeTickets,
        @Ge(0) @Le(1) @Nullable Double scatterReads,
        @Ge(0) @Le(1000) @Nullable Double routerOverheadMs,
        @Ge(1) @Le(50) @Nullable Double sstablesPerRead,
        @Default("auto") Toggle capacityUnits,
        @Default("on_demand") CapacityMode capacityMode,
        @Default("1000") @Gt(0) @Le(10_000_000) double readCapacityUnits,
        @Default("500") @Gt(0) @Le(10_000_000) double writeCapacityUnits,
        @Default("1.0") @Gt(0) @Le(400) double itemSizeKb,
        @Default("eventual") ConsistentReads consistentReads,
        @Gt(0) @Le(MAX_CPU_CORES) @Nullable Double cpuCores,
        @Default("0.5") @Ge(0) @Le(1) double cpuShare,
        @OmitWhenDefault @Gt(0) @Le(1) @Nullable Double siblingSpeed,
        @Gt(0) @Le(MAX_MEMORY_GIB) @Nullable Double memoryGib,
        @Gt(0) @Le(10_000_000) @Nullable Double workingSetGib,

        @Default("{\"kind\": \"lognormal\", \"median\": 1.0, \"p95\": 4.0}")
        Distribution diskReadMs,

        @Gt(0) @Le(MAX_STORAGE_GIB) @Nullable Double storageGib,
        @Matches(INSTANCE_TYPE) @Nullable String instanceType,
        @Gt(0) @Le(100_000_000) @Nullable Double dataSizeGib,
        @Default("0.35") @Ge(0) @Le(100_000) double costPerHour) {

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

    /** What each technology does by default (technology.py's {@code DATABASE_PROFILES}). */
    static final TechnologyProfiles<DatabaseTechnology> PROFILES = new TechnologyProfiles<>(
            DatabaseTechnology.class,
            Map.ofEntries(
                    Map.entry(DatabaseTechnology.POSTGRESQL, relational(TechnologyProfile.core(), "off", 1)),
                    Map.entry(DatabaseTechnology.MYSQL, relational(TechnologyProfile.experimental(), "off", 4)),
                    Map.entry(DatabaseTechnology.MARIADB, relational(TechnologyProfile.experimental(), "off", 4)),
                    Map.entry(DatabaseTechnology.ORACLE, relational(TechnologyProfile.experimental(), "off", 4)),
                    Map.entry(DatabaseTechnology.SQLSERVER, relational(TechnologyProfile.experimental(), "on", 4)),
                    Map.entry(DatabaseTechnology.COCKROACHDB, relational(TechnologyProfile.experimental(), "off", 4)),
                    Map.entry(DatabaseTechnology.YUGABYTEDB, relational(TechnologyProfile.experimental(), "off", 4)),
                    Map.entry(
                            DatabaseTechnology.MONGODB,
                            TechnologyProfile.experimental()
                                    .with("rowLocks", "on")
                                    .with("readsWaitForWrites", "off")
                                    .with("commitFlush", "on")
                                    .with("replication", "majority")
                                    .with("replicaApplyWorkers", 16)
                                    .with("readFrom", "primary")
                                    .with("readTickets", 128)
                                    .with("writeTickets", 128)
                                    .with("routerOverheadMs", 0.5)),
                    Map.entry(
                            DatabaseTechnology.COUCHBASE,
                            TechnologyProfile.experimental()
                                    .with("rowLocks", "off")
                                    .with("commitFlush", "off")
                                    .with("replication", "async")
                                    .with("replicaApplyWorkers", 4)
                                    .with("readFrom", "primary")),
                    Map.entry(DatabaseTechnology.CASSANDRA, wideColumn(2.0)),
                    Map.entry(DatabaseTechnology.SCYLLADB, wideColumn(1.5)),
                    Map.entry(
                            DatabaseTechnology.DYNAMODB,
                            TechnologyProfile.experimental()
                                    .with("rowLocks", "off")
                                    .with("commitFlush", "off")
                                    .with("replication", "none")
                                    .with("readFrom", "all")
                                    .with("capacityUnits", "on")),
                    Map.entry(DatabaseTechnology.ELASTICSEARCH, search()),
                    Map.entry(DatabaseTechnology.OPENSEARCH, search()),
                    Map.entry(
                            DatabaseTechnology.GENERIC,
                            TechnologyProfile.core()
                                    .with("rowLocks", "off")
                                    .with("readsWaitForWrites", "off")
                                    .with("commitFlush", "off")
                                    .with("replication", "none")
                                    .with("readFrom", "all"))));

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
                .with("replicaApplyWorkers", 4)
                .with("readFrom", "all")
                .with("scatterReads", 1.0)
                .with("coordinatorMs", 1.0);
    }

    public TechnologyProfile profile() {
        return PROFILES.of(technology);
    }

    /** Whether capacity is metered in units (DynamoDB-style), after the technology's default. */
    public boolean metered() {
        return profile().choice("capacityUnits", capacityUnits, Toggle.AUTO, Toggle.OFF) == Toggle.ON;
    }

    /** Which instances serve reads, after the technology's default. */
    public ReadFrom effectiveReadFrom() {
        return profile().choice("readFrom", readFrom, ReadFrom.AUTO, ReadFrom.AUTO);
    }

    /** The shards, or for a metered table the partitions its capacity and size need. */
    public int partitions() {
        if (!metered()) {
            return shards;
        }
        boolean provisioned = capacityMode == CapacityMode.PROVISIONED;
        double rcu = provisioned ? readCapacityUnits : ON_DEMAND_READ_UNITS;
        double wcu = provisioned ? writeCapacityUnits : ON_DEMAND_WRITE_UNITS;
        int byCapacity = (int) Math.ceil(rcu / PARTITION_READ_UNITS + wcu / PARTITION_WRITE_UNITS - 1e-9);
        int bySize = dataSizeGib != null && dataSizeGib != 0 ? (int) Math.ceil(dataSizeGib / PARTITION_GIB) : 1;
        return Math.max(1, Math.max(byCapacity, bySize));
    }
}
