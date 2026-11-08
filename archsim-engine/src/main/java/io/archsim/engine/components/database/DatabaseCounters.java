package io.archsim.engine.components.database;

/**
 * The names a database counts and averages under, as its report shows them.
 */
final class DatabaseCounters {

	/** The counter of reads served. */
	static final String READS = "reads";

	/** The counter of writes served. */
	static final String WRITES = "writes";

	/** The counter of reads sent to every shard. */
	static final String SCATTER_READS = "scatterReads";

	/**
	 * The prefix of each shard's counter of the operations it took, such as
	 * {@code shard0}.
	 */
	static final String SHARD = "shard";

	/** The counter of writes that waited for a row's lock. */
	static final String LOCK_WAITS = "lockWaits";

	/** The counter of reads that waited for a writer's lock. */
	static final String READS_BLOCKED = "readsBlocked";

	/** The counter of reads a replica served. */
	static final String REPLICA_READS = "replicaReads";

	/** The counter of reads from disk after a buffer-cache miss. */
	static final String DISK_READS = "diskReads";

	/** The mean time a write waited for its row's lock. */
	static final String LOCK_WAIT_MS = "lockWaitMs";

	/** The mean time a write waited for its log's flush. */
	static final String COMMIT_WAIT_MS = "commitWaitMs";

	/** The mean time an operation waited for a ticket. */
	static final String TICKET_WAIT_MS = "ticketWaitMs";

	/** The mean time a write waited for its replicas' acknowledgements. */
	static final String REPLICA_ACK_MS = "replicaAckMs";

	private DatabaseCounters() {
	}

}
