package io.archsim.engine.components.database;

import java.util.Arrays;
import java.util.OptionalDouble;
import java.util.function.DoubleSupplier;

import io.archsim.engine.random.PythonRandom;
import io.archsim.engine.random.Sampler;
import io.archsim.model.document.RequestType;

/**
 * What an operation on a database draws: its time on a connection, whether a read misses
 * the buffer cache, and the disk reads a miss costs.
 */
final class OperationDraws {

	private final DatabaseSettings settings;

	private final ServiceTimes serviceTimes;

	private final PythonRandom buffer;

	private final Sampler diskRead;

	private final OptionalDouble bufferHit;

	/**
	 * A database's draws.
	 * @param settings its settings in effect
	 * @param serviceTimes draws a read's or a write's time on one copy
	 * @param buffer the stream the buffer cache and the disk draw from
	 */
	OperationDraws(DatabaseSettings settings, ServiceTimes serviceTimes, PythonRandom buffer) {
		this.settings = settings;
		this.serviceTimes = serviceTimes;
		this.buffer = buffer;
		this.diskRead = Sampler.of(settings.config().diskReadMs());
		this.bufferHit = settings.bufferHitRatio();
	}

	/**
	 * An operation's time: one copy's, or, with several copies of each key, the time of
	 * the copy that answers last of those the consistency level waits for.
	 * @param operation a read or a write
	 * @return milliseconds
	 */
	double serviceMs(RequestType operation) {
		DoubleSupplier copy = (operation == RequestType.READ) ? this.serviceTimes.read() : this.serviceTimes.write();
		int copies = this.settings.config().replicationFactor();
		if (copies <= 1) {
			return copy.getAsDouble();
		}
		double[] times = new double[copies];
		for (int index = 0; index < copies; index++) {
			times[index] = copy.getAsDouble();
		}
		Arrays.sort(times);
		return times[this.settings.copiesWaitedFor() - 1];
	}

	/**
	 * Draws whether a read misses the buffer cache.
	 * @param operation a read or a write
	 * @return {@code true} for a read that goes to disk
	 */
	boolean missesBuffer(RequestType operation) {
		if (operation != RequestType.READ || this.bufferHit.isEmpty() || this.bufferHit.getAsDouble() >= 1.0) {
			return false;
		}
		return this.buffer.nextDouble() >= this.bufferHit.getAsDouble();
	}

	/**
	 * Draws how many disk reads a read that missed the buffer cache makes: the SSTables
	 * it touches, a fraction of one drawn as one more read or none.
	 * @return at least one
	 */
	int diskReads() {
		double sstables = this.settings.sstablesPerRead();
		int whole = (int) sstables;
		if (sstables > whole && this.buffer.nextDouble() < sstables - whole) {
			whole++;
		}
		return Math.max(1, whole);
	}

	/**
	 * Draws one disk read's time.
	 * @return milliseconds
	 */
	double diskReadMs() {
		return this.diskRead.sample(this.buffer);
	}

	/**
	 * Draws an operation's time on one copy of its key, a slowdown planned for the
	 * database included.
	 *
	 * @param read a read's
	 * @param write a write's
	 */
	record ServiceTimes(DoubleSupplier read, DoubleSupplier write) {
	}

}
