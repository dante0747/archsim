package io.archsim.engine.estimate;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.queueing.Moments;

/**
 * How a part serves requests, as the formulas need it.
 *
 * @param read its work for a read
 * @param write its work for a write
 * @param flush how long a write then waits for a database's log flush, which takes none
 * of its CPU and no longer on slower threads; zero for other parts
 * @param publish a queue's time to take a message, which is all its producers wait for;
 * {@code null} for other parts
 * @param pools its pools, each with its own slots and queue: replicas, instances, shards
 * @param slots the requests a pool handles at once; {@code null} for no limit
 * @param queues whether a request waits for a free slot (an M/M/c queue) rather than
 * being turned away
 * @param blocks whether a slot stays held while the part waits on its own calls
 * @param cpu the share of its CPU in use at the peak; {@code null} without a CPU size
 * @param limit the share of a rate or throughput limit used at the peak; {@code null}
 * without one
 * @param populate whether it writes a value back after a miss (a read-through cache)
 */
record PartShape(Moments read, Moments write, Moments flush, @Nullable Moments publish, int pools,
		@Nullable Integer slots, boolean queues, boolean blocks, @Nullable Double cpu, @Nullable Double limit,
		boolean populate) {

	/**
	 * Its own time for a kind of request: its work, and for a write the log flush it then
	 * waits for.
	 * @param op reads or writes
	 * @return the time
	 */
	Moments own(Op op) {
		return (op == Op.READ) ? this.read : this.write.plus(this.flush);
	}

	/**
	 * This part with its CPU work at a speed below full (its machine's threads slow each
	 * other): more of its CPU busy, and the CPU share of its work longer. A log flush
	 * takes as long as it did.
	 * @param speed its threads' speed, as a share of full speed
	 * @param cpuShare the share of its work that needs a core
	 * @return the slowed part; the same part without a CPU size or a speed
	 */
	PartShape slowedTo(double speed, double cpuShare) {
		if (this.cpu == null || speed <= 0) {
			return this;
		}
		double factor = cpuShare / speed + (1.0 - cpuShare);
		Moments slowedPublish = (this.publish != null) ? this.publish.scaledBy(factor) : null;
		return new PartShape(this.read.scaledBy(factor), this.write.scaledBy(factor), this.flush, slowedPublish,
				this.pools, this.slots, this.queues, this.blocks, this.cpu / speed, this.limit, this.populate);
	}

}
