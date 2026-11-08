package io.archsim.engine.components.database;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Signal;

/**
 * A write shipped to a shard's replicas: its place in the shard's commit order, the time
 * its replay takes, and, when the write waits for replicas, the moment enough of them
 * have replayed it.
 */
final class ApplyJob {

	private final int sequence;

	private final double costMs;

	private final int acksNeeded;

	private final @Nullable Signal acknowledged;

	private int acks;

	/**
	 * A write to replay.
	 * @param sequence its sequence number on its shard
	 * @param costMs the time its replay takes
	 * @param acksNeeded the replicas the write waits for
	 * @param acknowledged happens once that many replayed it; {@code null} when the write
	 * waits for none
	 */
	ApplyJob(int sequence, double costMs, int acksNeeded, @Nullable Signal acknowledged) {
		this.sequence = sequence;
		this.costMs = costMs;
		this.acksNeeded = acksNeeded;
		this.acknowledged = acknowledged;
	}

	int sequence() {
		return this.sequence;
	}

	double costMs() {
		return this.costMs;
	}

	int acksNeeded() {
		return this.acksNeeded;
	}

	/**
	 * The moment enough replicas have replayed the write.
	 * @return the signal; {@code null} when the write waits for no replica
	 */
	@Nullable Signal acknowledged() {
		return this.acknowledged;
	}

	/**
	 * Counts a replica's replay, which acknowledges the write once enough have.
	 */
	void acknowledge() {
		if (this.acknowledged == null) {
			return;
		}
		this.acks++;
		if (this.acks == this.acksNeeded) {
			this.acknowledged.succeed();
		}
	}

}
