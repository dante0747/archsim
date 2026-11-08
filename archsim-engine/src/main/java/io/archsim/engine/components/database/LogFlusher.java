package io.archsim.engine.components.database;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

import io.archsim.engine.kernel.Signal;
import io.archsim.engine.kernel.Simulation;

/**
 * A shard's write-ahead log, flushed with group commit: one flush at a time, and the
 * commits that arrive while a flush runs share the next one. Each commit waits for the
 * end of the flush that covers it.
 */
final class LogFlusher {

	private final Simulation simulation;

	private final DoubleSupplier flushMs;

	private final List<Signal> pending = new ArrayList<>();

	private boolean flushing;

	private long flushes;

	private long commits;

	/**
	 * A shard's log, nothing flushed yet.
	 * @param simulation the run
	 * @param flushMs draws a flush's time, in milliseconds
	 */
	LogFlusher(Simulation simulation, DoubleSupplier flushMs) {
		this.simulation = simulation;
		this.flushMs = flushMs;
	}

	/**
	 * Commits a write: it joins the next flush, which starts now unless one is running.
	 * @return the end of the flush that covers it
	 */
	Signal commit() {
		Signal flushed = this.simulation.event();
		this.pending.add(flushed);
		if (!this.flushing) {
			this.flushing = true;
			this.simulation.start(this::flushPending);
		}
		return flushed;
	}

	/**
	 * How many flushes the log ran.
	 * @return the flushes over the whole run
	 */
	long flushes() {
		return this.flushes;
	}

	/**
	 * How many commits the flushes covered.
	 * @return the commits over the whole run
	 */
	long commits() {
		return this.commits;
	}

	// flushes the commits waiting, then those that came meanwhile, until none waits
	private void flushPending(Runnable finish) {
		if (this.pending.isEmpty()) {
			this.flushing = false;
			finish.run();
			return;
		}
		List<Signal> batch = List.copyOf(this.pending);
		this.pending.clear();
		this.flushes++;
		this.commits += batch.size();
		this.simulation.after(this.flushMs.getAsDouble(), () -> {
			batch.forEach(Signal::succeed);
			flushPending(finish);
		});
	}

}
