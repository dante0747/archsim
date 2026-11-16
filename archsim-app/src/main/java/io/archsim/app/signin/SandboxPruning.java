package io.archsim.app.signin;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the sandboxes that expired, with their guests, when the API starts and every
 * minute after: the API owns them. Whatever the setting says now, so sandboxes made
 * before it was turned off still go. Every instance prunes, and finds nothing that
 * another deleted first. A guest the database fails to delete is logged and left for the
 * next prune, and the others still go; so is a prune that cannot list them.
 */
@Component
@Profile("api")
class SandboxPruning {

	/** {@link #PERIOD} in minutes, for {@code @Scheduled}. */
	static final long MINUTES_APART = 1;

	/** The time from the end of one prune to the start of the next. */
	static final Duration PERIOD = Duration.ofMinutes(MINUTES_APART);

	private static final Logger LOGGER = LoggerFactory.getLogger(SandboxPruning.class);

	private final Guests guests;

	SandboxPruning(Guests guests) {
		this.guests = guests;
	}

	@Scheduled(initialDelay = 0, fixedDelay = MINUTES_APART, timeUnit = TimeUnit.MINUTES)
	void prune() {
		List<String> expired;
		try {
			expired = this.guests.expired();
		}
		catch (DataAccessException ex) {
			LOGGER.warn("could not list the expired sandboxes: {}", ex.getMessage());
			return;
		}
		int deleted = 0;
		for (String guestId : expired) {
			if (delete(guestId)) {
				deleted++;
			}
		}
		LOGGER.debug("deleted {} of {} expired sandbox(es)", deleted, expired.size());
	}

	/**
	 * Deletes one guest and their sandbox, in a transaction of its own.
	 * @param guestId the guest
	 * @return {@code true} when they were deleted here: not when another instance is
	 * deleting them, or the database failed
	 */
	private boolean delete(String guestId) {
		try {
			return this.guests.delete(guestId);
		}
		catch (DataAccessException ex) {
			LOGGER.warn("could not delete the expired sandbox of guest {}: {}", guestId, ex.getMessage());
			return false;
		}
	}

}
