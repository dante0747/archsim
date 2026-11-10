package io.archsim.app.limits;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the rate-limit counters of windows that ended, when the API starts and every
 * minute after: the API owns the counters. Every instance prunes, and finds nothing that
 * another deleted first. A prune the database fails is logged, and the next runs as
 * planned.
 */
@Component
@Profile("api")
class RateLimitPruning {

	private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitPruning.class);

	private final RateLimits limits;

	RateLimitPruning(RateLimits limits) {
		this.limits = limits;
	}

	@Scheduled(initialDelay = 0, fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
	void prune() {
		try {
			int pruned = this.limits.pruneEnded();
			LOGGER.debug("pruned {} rate-limit counter(s) of ended windows", pruned);
		}
		catch (DataAccessException ex) {
			LOGGER.warn("could not prune the rate-limit counters: {}", ex.getMessage());
		}
	}

}
