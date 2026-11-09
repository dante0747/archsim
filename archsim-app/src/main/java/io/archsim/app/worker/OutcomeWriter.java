package io.archsim.app.worker;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Writes how a worker's runs ended, or gives them back, without losing a write to a brief
 * database outage: each write is tried three times, a second and then two apart, as the
 * Python worker did, and only while the worker holds the run.
 */
final class OutcomeWriter {

	private static final Logger LOGGER = LoggerFactory.getLogger(OutcomeWriter.class);

	/** The tries a write gets. */
	private static final int ATTEMPTS = 3;

	private final LeaseRepository leases;

	private final String workerId;

	private final Clock clock;

	private final Pause pause;

	OutcomeWriter(LeaseRepository leases, String workerId, Clock clock, Pause pause) {
		this.leases = leases;
		this.workerId = workerId;
		this.clock = clock;
		this.pause = pause;
	}

	/**
	 * Writes how a run ended, dropped when the worker no longer holds it.
	 * @param runId the run
	 * @param outcome how it ended
	 */
	void finish(String runId, RunOutcome outcome) {
		Boolean written = tried("recording run " + runId,
				() -> this.leases.finish(runId, this.workerId, outcome, this.clock.instant()));
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
				() -> this.leases.release(runId, this.workerId, this.clock.instant()));
		LOGGER.info("run {}: given back ({})", runId,
				(released != null) ? released.map(Released::status).orElse("no longer held") : "not known");
	}

	private <T> @Nullable T tried(String what, Supplier<T> write) {
		for (int attempt = 1;; attempt++) {
			try {
				return write.get();
			}
			catch (DataAccessException ex) {
				LOGGER.warn("{} failed (attempt {} of {})", what, attempt, ATTEMPTS, ex);
				if (attempt == ATTEMPTS) {
					return null;
				}
				this.pause.pause(Duration.ofSeconds(attempt));
			}
		}
	}

}
