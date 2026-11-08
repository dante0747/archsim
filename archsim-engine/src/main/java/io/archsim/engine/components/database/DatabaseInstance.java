package io.archsim.engine.components.database;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Resource;
import io.archsim.engine.runtime.Cores;
import io.archsim.engine.runtime.WorkerPool;
import io.archsim.model.document.RequestType;

/**
 * One instance of a database's shard, its primary or a read replica: its connections with
 * their wait queue, its CPU, and the tickets that limit the reads and writes it runs at
 * once.
 *
 * @param index its place in the shard: 0 for the primary, from 1 for the replicas
 * @param pool its connections and their wait queue
 * @param cores its CPU
 * @param readTickets the reads it runs at once; {@code null} when tickets do not limit
 * them
 * @param writeTickets the writes it runs at once; {@code null} when tickets do not limit
 * them
 * @param log a replica's replay of its primary's writes; {@code null} for the primary, or
 * without replication
 */
record DatabaseInstance(int index, WorkerPool pool, Cores cores, @Nullable Resource readTickets,
		@Nullable Resource writeTickets, @Nullable ReplicaLog log) {

	/**
	 * The tickets that limit an operation.
	 * @param operation a read or a write
	 * @return its tickets; {@code null} when tickets do not limit it
	 */
	@Nullable Resource tickets(RequestType operation) {
		return (operation == RequestType.WRITE) ? this.writeTickets : this.readTickets;
	}

	/**
	 * Whether the instance is a read replica.
	 * @return {@code true} for a replica, {@code false} for the primary
	 */
	boolean isReplica() {
		return this.index > 0;
	}

}
