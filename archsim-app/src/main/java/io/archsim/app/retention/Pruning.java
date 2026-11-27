package io.archsim.app.retention;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.archsim.app.store.Times;

/**
 * Prunes the rows each feature keeps only for a while ({@link Retention}), one retention
 * after another, when the API starts and every hour after: the API owns them. Every
 * instance prunes, and the batches of two pruning at once share the rows. A retention the
 * database fails is logged, the others still run, and the next prune tries it again. The
 * rate-limit counters and the sandboxes, which must go within a minute, have pruning of
 * their own.
 */
@Component
@Profile("api")
class Pruning {

	/** The time from the end of one prune to the start of the next, in hours. */
	static final long HOURS_APART = 1;

	private static final Logger LOGGER = LoggerFactory.getLogger(Pruning.class);

	private final List<Retention> retentions;

	private final Clock clock;

	Pruning(List<Retention> retentions, Clock clock) {
		this.retentions = List.copyOf(retentions);
		this.clock = clock;
	}

	@Scheduled(initialDelay = 0, fixedDelay = HOURS_APART, timeUnit = TimeUnit.HOURS)
	void prune() {
		Instant now = Times.now(this.clock);
		this.retentions.forEach((retention) -> prune(retention, now));
	}

	/**
	 * Runs one retention, and logs what it pruned, or why it could not.
	 * @param retention the retention
	 * @param now the time it is
	 */
	private void prune(Retention retention, Instant now) {
		try {
			int pruned = retention.prune(now);
			if (pruned > 0) {
				LOGGER.info("pruned {} {}", pruned, retention.description());
			}
		}
		catch (DataAccessException ex) {
			LOGGER.warn("could not prune the {}: {}", retention.description(), ex.getMessage());
		}
	}

}
