package io.archsim.app.worker;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.simulator.RunObserver;
import io.archsim.engine.simulator.SimulationCancelledException;

/**
 * One claimed run, from its start to its written outcome (ADR-0053). It simulates on one
 * of the worker's platform threads, writes its progress at most once a second, and is
 * asked to stop when someone cancels it, its worker gives it back or loses it, or it
 * outlives its hard deadline. Its outcome is written once: a run that will not stop is
 * written cancelled once its grace is over, or failed at its deadline, and keeps its
 * thread until it ends.
 */
final class Execution implements RunObserver {

	private static final Logger LOGGER = LoggerFactory.getLogger(Execution.class);

	/** The most often a run's progress is written. */
	private static final long PROGRESS_EVERY = Duration.ofSeconds(1).toNanos();

	private static final double NANOS_PER_SECOND = 1e9;

	private final ClaimedRun run;

	private final RunParts parts;

	private final SimulationLimits limits;

	private final AtomicBoolean cancel = new AtomicBoolean();

	private final AtomicReference<@Nullable Abandoned> abandoned = new AtomicReference<>();

	private final AtomicBoolean overran = new AtomicBoolean();

	private final AtomicBoolean settled = new AtomicBoolean();

	// done once the outcome is written, on whichever thread wrote it
	private final CompletableFuture<Void> written = new CompletableFuture<>();

	private final AtomicLong lastProgress;

	private final RunTimers timers;

	Execution(ClaimedRun run, RunParts parts) {
		this.run = run;
		this.parts = parts;
		this.limits = run.limits(parts.limits());
		this.timers = new RunTimers(parts.timers());
		this.lastProgress = new AtomicLong(parts.nanos().getAsLong() - PROGRESS_EVERY);
	}

	String id() {
		return this.run.id();
	}

	/**
	 * Starts simulating the run, its hard deadline counted from now.
	 * @return done once its outcome is written, or it is given back, and its thread is
	 * free
	 */
	CompletableFuture<Void> start() {
		LOGGER.info("run {}: attempt {} on worker {}", id(), this.run.attempts(), this.parts.workerId());
		Duration deadline = Duration.ofNanos((long) (this.limits.maxWallSeconds() * NANOS_PER_SECOND))
			.plus(this.parts.deadlineMargin());
		this.timers.after(deadline, this::overrun);
		return CompletableFuture.supplyAsync(this::simulate, this.parts.runs())
			.handle(Ended::new)
			.thenAccept(this::ended)
			.exceptionally((fault) -> {
				faulted(fault);
				return null;
			})
			.thenCompose((ended) -> this.settled.get() ? this.written : CompletableFuture.completedFuture(null));
	}

	/**
	 * Asks the run to stop, as someone asked; it is written cancelled after its grace.
	 */
	void requestCancel() {
		if (this.cancel.compareAndSet(false, true)) {
			this.timers.after(this.parts.cancelGrace(), () -> settle(new RunOutcome.Cancelled()));
		}
	}

	/**
	 * Asks the run to stop, writing nothing of it: the worker gives it back, or lost it.
	 * @param reason why
	 */
	void abandon(Abandoned reason) {
		this.abandoned.compareAndSet(null, reason);
	}

	@Override
	public void progress(double fraction) {
		long now = this.parts.nanos().getAsLong();
		if (now - this.lastProgress.get() < PROGRESS_EVERY) {
			return;
		}
		this.lastProgress.set(now);
		try {
			if (!this.parts.leases().progress(id(), this.parts.workerId(), fraction)) {
				abandon(Abandoned.LOST);
			}
		}
		catch (DataAccessException ex) {
			LOGGER.debug("could not record progress of run {}", id(), ex);
		}
	}

	@Override
	public boolean cancelRequested() {
		return this.cancel.get() || this.abandoned.get() != null || this.overran.get();
	}

	private JsonNode simulate() {
		return this.parts.simulator().simulate(this.run.document(), this.run.request(this.limits), this);
	}

	private void ended(Ended ended) {
		this.timers.callOff();
		Throwable failure = ended.cause();
		if (failure instanceof SimulationCancelledException) {
			stopped();
			return;
		}
		JsonNode report = ended.report();
		settle((report != null) ? this.parts.outcomes().succeeded(report)
				: this.parts.outcomes().failed(Objects.requireNonNull(failure)));
	}

	// a run that stopped when asked: given back, lost to another worker, or cancelled
	private void stopped() {
		Abandoned reason = this.abandoned.get();
		if (reason == Abandoned.REQUEUE && !this.settled.get()) {
			this.parts.writer().release(id());
		}
		else if (reason == Abandoned.LOST) {
			LOGGER.warn("run {}: this worker lost its lease; its run was stopped", id());
		}
		else {
			settle(new RunOutcome.Cancelled());
		}
	}

	// the outcome claimed, then the run asked to stop, then the failure written: a run
	// that stops when asked is not written cancelled, and the database cannot hold it up
	private void overrun() {
		RunOutcome failed = this.parts.outcomes().overran(this.limits.maxWallSeconds());
		boolean first = this.settled.compareAndSet(false, true);
		this.overran.set(true);
		if (first) {
			write(failed);
		}
	}

	// the Python worker's last resort: whatever went wrong after the simulation fails the
	// run, unless its outcome was claimed already
	private void faulted(Throwable fault) {
		Throwable cause = Ended.unwrapped(fault);
		LOGGER.error("run {}: the worker failed while running it", id(), cause);
		settle(this.parts.outcomes().failed(cause));
	}

	private void settle(RunOutcome outcome) {
		if (this.settled.compareAndSet(false, true)) {
			write(outcome);
		}
	}

	private void write(RunOutcome outcome) {
		try {
			this.parts.writer().finish(id(), outcome);
		}
		finally {
			this.written.complete(null);
		}
	}

	/**
	 * Why a worker stops a run without writing how it ended.
	 */
	enum Abandoned {

		/** The worker is stopping: the run is given back. */
		REQUEUE,

		/** Another worker may hold the run now. */
		LOST

	}

	/**
	 * How a simulation ended: its report, or what it threw.
	 *
	 * @param report the report, if it simulated
	 * @param failure what it threw, wrapped as the future wraps it
	 */
	private record Ended(@Nullable JsonNode report, @Nullable Throwable failure) {

		@Nullable Throwable cause() {
			return (this.failure != null) ? unwrapped(this.failure) : null;
		}

		static Throwable unwrapped(Throwable failure) {
			return (failure instanceof CompletionException wrapped && wrapped.getCause() != null) ? wrapped.getCause()
					: failure;
		}

	}

}
