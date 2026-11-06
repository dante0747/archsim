package io.archsim.app.store;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock whose instants, to the microsecond the database stores, never repeat and never
 * go back: rows made within one tick of the system clock, or after the system clock is
 * set back, still get different times, in the order they were made. After its source is
 * set back, it moves on only a microsecond per reading until the source catches up. It
 * counts within one process, and is not serializable.
 */
public final class StrictlyIncreasingClock extends Clock {

	private final Clock source;

	private final AtomicReference<Instant> last;

	/**
	 * A clock that tells its source's time to the microsecond, moved on by a microsecond
	 * whenever that would repeat or go back.
	 * @param source the clock it reads
	 */
	public StrictlyIncreasingClock(Clock source) {
		this(source, new AtomicReference<>(Instant.MIN));
	}

	private StrictlyIncreasingClock(Clock source, AtomicReference<Instant> last) {
		this.source = source;
		this.last = last;
	}

	@Override
	public ZoneId getZone() {
		return this.source.getZone();
	}

	/**
	 * This clock in another time zone, counting on from the same last instant.
	 * @param zone the time zone
	 * @return the clock
	 */
	@Override
	public Clock withZone(ZoneId zone) {
		return new StrictlyIncreasingClock(this.source.withZone(zone), this.last);
	}

	@Override
	public Instant instant() {
		return this.last.accumulateAndGet(Times.now(this.source), StrictlyIncreasingClock::after);
	}

	private static Instant after(Instant last, Instant now) {
		return now.isAfter(last) ? now : last.plus(1, ChronoUnit.MICROS);
	}

}
