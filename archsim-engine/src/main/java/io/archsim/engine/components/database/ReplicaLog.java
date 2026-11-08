package io.archsim.engine.components.database;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.kernel.Store;

/**
 * A read replica's replay of its primary's writes: the writes arrive in commit order and
 * a few workers replay them, perhaps out of order. The replica has applied every write up
 * to the highest sequence number with no earlier write still to replay.
 */
final class ReplicaLog {

	private final Store<ApplyJob> arrived;

	/** The writes not replayed yet, by sequence number, with the time each committed. */
	private final Map<Integer, Double> pending = new LinkedHashMap<>();

	/** The writes replayed beyond the applied one, lowest first. */
	private final Queue<Integer> replayed = new PriorityQueue<>();

	private int applied;

	/**
	 * A replica's log, nothing written yet.
	 * @param simulation the run
	 */
	ReplicaLog(Simulation simulation) {
		this.arrived = new Store<>(simulation);
	}

	/**
	 * The writes that reached the replica and wait for a worker.
	 * @return the queue
	 */
	Store<ApplyJob> arrived() {
		return this.arrived;
	}

	/**
	 * The highest sequence number up to which every write is replayed.
	 * @return the sequence number; 0 before any
	 */
	int applied() {
		return this.applied;
	}

	/**
	 * Whether writes committed on the primary wait to be replayed here.
	 * @return {@code true} while any does
	 */
	boolean behind() {
		return !this.pending.isEmpty();
	}

	/**
	 * Notes a write the primary committed, which the replica must replay.
	 * @param sequence the write's sequence number on its shard
	 * @param committedAt when it committed
	 */
	void expect(int sequence, double committedAt) {
		this.pending.put(sequence, committedAt);
	}

	/**
	 * Notes a write replayed, and moves the applied mark past every write replayed in
	 * order.
	 * @param sequence the write's sequence number
	 */
	void replayed(int sequence) {
		this.pending.remove(sequence);
		this.replayed.add(sequence);
		Integer lowest = this.replayed.peek();
		while (lowest != null && lowest == this.applied + 1) {
			this.replayed.remove();
			this.applied++;
			lowest = this.replayed.peek();
		}
	}

	/**
	 * How far the replica is behind its primary: the age of the oldest write it has not
	 * replayed, the next in order when it waits.
	 * @param now the time
	 * @return milliseconds; 0 when it has replayed every write
	 */
	double lag(double now) {
		if (this.pending.isEmpty()) {
			return 0.0;
		}
		Double next = this.pending.get(this.applied + 1);
		double oldest = (next != null) ? next : Collections.min(this.pending.values());
		return Math.max(0.0, now - oldest);
	}

}
