package io.archsim.app.store;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Times as PostgreSQL stores them: to the microsecond.
 */
public final class Times {

	private Times() {
	}

	/**
	 * The time now, as it is stored and read back.
	 * @param clock the clock
	 * @return the time, to the microsecond
	 */
	public static Instant now(Clock clock) {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

}
