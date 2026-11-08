package io.archsim.engine.components.database;

import java.util.OptionalDouble;
import java.util.OptionalInt;

import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.component.Toggle;
import io.archsim.model.component.database.Consistency;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.database.ReadFrom;
import io.archsim.model.component.database.Replication;

/**
 * A database's behaviour in effect during a run: each setting as set, unless it is auto
 * or not set; then its technology's default, or the engine's when the technology has
 * none.
 */
final class DatabaseSettings {

	/**
	 * The threads a replica replays writes with, when neither it nor its technology says.
	 */
	private static final int DEFAULT_APPLY_WORKERS = 4;

	private final DatabaseConfig config;

	private final TechnologyProfile profile;

	private final int partitions;

	/**
	 * A database's settings in effect.
	 * @param config its settings
	 */
	DatabaseSettings(DatabaseConfig config) {
		this.config = config;
		this.profile = config.profile();
		this.partitions = config.partitions();
	}

	DatabaseConfig config() {
		return this.config;
	}

	/**
	 * The partitions of its keyspace: its shards, or a metered table's partitions.
	 * @return at least one
	 */
	int partitions() {
		return this.partitions;
	}

	/**
	 * Whether writes to a row wait for each other.
	 * @return {@code true} with row locks
	 */
	boolean locksRows() {
		return on("rowLocks", this.config.rowLocks());
	}

	/**
	 * Whether a read of a row waits for the write that holds its lock.
	 * @return {@code true} with locking reads
	 */
	boolean readersWaitForWriters() {
		return on("readsWaitForWrites", this.config.readsWaitForWrites());
	}

	/**
	 * What a write waits for from the read replicas.
	 * @return the replication in effect; none without read replicas
	 */
	Replication replication() {
		if (this.config.readReplicas() == 0) {
			return Replication.NONE;
		}
		return this.profile.choice("replication", this.config.replication(), Replication.AUTO, Replication.NONE);
	}

	/**
	 * The threads a replica replays writes with.
	 * @return the workers; 4 when neither the database nor its technology says
	 */
	int applyWorkers() {
		return this.profile.wholeNumber("replicaApplyWorkers", this.config.replicaApplyWorkers())
			.orElse(DEFAULT_APPLY_WORKERS);
	}

	/**
	 * The replicas a write waits for: a majority of the copies, the primary being one, or
	 * all of them.
	 * @return the acknowledgements; none with asynchronous replication or no replication
	 */
	int replicaAcks() {
		int replicas = this.config.readReplicas();
		return switch (replication()) {
			case ALL -> replicas;
			case MAJORITY -> (1 + replicas) / 2;
			case AUTO, ASYNC, NONE -> 0;
		};
	}

	/**
	 * Which instances serve reads.
	 * @return the primary, the replicas, or all of them, which is the engine's default
	 */
	ReadFrom readFrom() {
		return this.profile.choice("readFrom", this.config.readFrom(), ReadFrom.AUTO, ReadFrom.ALL);
	}

	/**
	 * The share of reads sent to every shard.
	 * @return the share; none with a single partition
	 */
	double scatterShare() {
		if (this.partitions <= 1) {
			return 0.0;
		}
		return this.profile.number("scatterReads", this.config.scatterReads()).orElse(0.0);
	}

	/**
	 * The time a query router adds to each request.
	 * @return milliseconds; none when neither the database nor its technology says
	 */
	double routerOverheadMs() {
		return this.profile.number("routerOverheadMs", this.config.routerOverheadMs()).orElse(0.0);
	}

	/**
	 * The SSTables a read that misses the buffer cache touches on disk.
	 * @return on average; one when neither the database nor its technology says
	 */
	double sstablesPerRead() {
		return this.profile.number("sstablesPerRead", this.config.sstablesPerRead()).orElse(1.0);
	}

	/**
	 * The reads an instance runs at once.
	 * @return the tickets; empty when they do not limit reads
	 */
	OptionalInt readTickets() {
		return this.profile.wholeNumber("readTickets", this.config.readTickets());
	}

	/**
	 * The writes an instance runs at once.
	 * @return the tickets; empty when they do not limit writes
	 */
	OptionalInt writeTickets() {
		return this.profile.wholeNumber("writeTickets", this.config.writeTickets());
	}

	/**
	 * The share of reads served from memory: the share of the working set a shard keeps
	 * in its buffer cache.
	 * @return the share, at most 1; empty without a memory size and a working set
	 */
	OptionalDouble bufferHitRatio() {
		Double memoryGib = this.config.memoryGib();
		Double workingSetGib = this.config.workingSetGib();
		if (memoryGib == null || workingSetGib == null) {
			return OptionalDouble.empty();
		}
		double perShard = workingSetGib / this.partitions;
		return OptionalDouble.of(Math.min(1.0, DatabaseConfig.BUFFER_CACHE_SHARE * memoryGib / perShard));
	}

	/**
	 * The copies of a key an operation waits for, by its consistency level.
	 * @return all of them, a majority, or one
	 */
	int copiesWaitedFor() {
		int copies = this.config.replicationFactor();
		if (this.config.consistency() == Consistency.ALL) {
			return copies;
		}
		return (this.config.consistency() == Consistency.QUORUM) ? copies / 2 + 1 : 1;
	}

	private boolean on(String field, Toggle explicit) {
		return this.profile.choice(field, explicit, Toggle.AUTO, Toggle.OFF) == Toggle.ON;
	}

}
