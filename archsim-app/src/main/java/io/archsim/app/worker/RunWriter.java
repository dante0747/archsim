package io.archsim.app.worker;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * Writes a worker's runs: their progress, and how they ended or their giving back, the
 * latter without losing a write to a brief database outage: tried three times, a second
 * and then two apart, as the Python worker did. Every write needs the worker to hold the
 * run.
 */
final class RunWriter {

	private static final Logger LOGGER = LoggerFactory.getLogger(RunWriter.class);

	/** The tries a write gets. */
	private static final int ATTEMPTS = 3;

	private final LeaseRepository leases;

	private final WorkerContext context;

	private final Pause pause;

	RunWriter(LeaseRepository leases, WorkerContext context, Pause pause) {
		this.leases = leases;
		this.context = context;
		this.pause = pause;
	}

	/**
	 * Writes how far a run has got, if the worker holds it. A write the database misses
	 * is left for the next.
	 * @param runId the run
	 * @param fraction how far it got
	 * @return {@code false} when another worker holds the run now
	 */
	boolean progress(String runId, double fraction) {
		try {
			return this.leases.progress(runId, this.context.workerId(), fraction);
		}
		catch (DataAccessException ex) {
			LOGGER.debug("could not record progress of run {}", runId, ex);
			return true;
		}
	}

	/**
	 * Writes how a run ended, dropped when the worker no longer holds it.
	 * @param runId the run
	 * @param outcome how it ended
	 */
	void finish(String runId, RunOutcome outcome) {
		Boolean written = tried("recording run " + runId,
				() -> this.leases.finish(runId, this.context.workerId(), outcome, this.context.clock().instant()));
		if (Boolean.TRUE.equals(written)) {
			LOGGER.info("run {}: {}", runId, outcome.status());
		}
		else {
			LOGGER.warn("run {}: lease lost before it finished; its {} outcome was dropped", runId, outcome.status());
		}
	}

	/**
	 * Gives back a run the worker stops without finishing.
	 * @param runId the run
	 */
	void release(String runId) {
		Optional<Released> released = tried("giving back run " + runId,
				() -> this.leases.release(runId, this.context.workerId(), this.context.clock().instant()));
		LOGGER.info("run {}: given back ({})", runId,
				(released != null) ? released.map(Released::status).orElse("no longer held") : "not known");
	}

	private <T> @Nullable T tried(String what, Supplier<T> write) {
		for (int attempt = 1;; attempt++) {
			try {
				return write.get();
			}
			catch (DataAccessException | TransactionException ex) {
				LOGGER.warn("{} failed (attempt {} of {})", what, attempt, ATTEMPTS, ex);
				if (attempt == ATTEMPTS) {
					return null;
				}
				this.pause.pause(Duration.ofSeconds(attempt));
			}
		}
	}

}
