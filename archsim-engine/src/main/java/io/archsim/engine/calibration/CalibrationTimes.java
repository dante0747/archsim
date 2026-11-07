package io.archsim.engine.calibration;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import io.archsim.engine.text.PythonStrings;

/**
 * Times as calibration reads and writes them: ISO 8601 read as Python's
 * {@code datetime.fromisoformat} reads it (a time without an offset is UTC), and written
 * to the second, {@code 2026-10-01T00:00:00Z}.
 */
final class CalibrationTimes {

	private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
		.withZone(ZoneOffset.UTC);

	private static final double MICROS_PER_SECOND = 1e6;

	private static final double NANOS_PER_SECOND = 1e9;

	private static final int MICRO_DIGITS = 6;

	private CalibrationTimes() {
	}

	/**
	 * Now, to the second.
	 * @param clock the clock
	 * @return the time, such as {@code 2026-11-02T00:00:00Z}
	 */
	static String now(Clock clock) {
		return SECONDS.format(clock.instant().truncatedTo(ChronoUnit.SECONDS));
	}

	/**
	 * A time in seconds since the epoch, as the Python engine's {@code parse_time} reads
	 * it: stripped, {@code Z} anywhere read as {@code +00:00}, then as Python 3.12's
	 * {@code datetime.fromisoformat} reads it ({@link IsoFormat}), UTC when it has no
	 * offset.
	 * @param text the time
	 * @return its seconds since the epoch, to the microsecond
	 * @throws IllegalArgumentException in Python's words when it is not ISO 8601 or a
	 * field is out of range
	 */
	static double parse(String text) {
		long micros = IsoFormat.epochMicros(PythonStrings.strip(text).replace("Z", "+00:00"));
		return BigDecimal.valueOf(micros, MICRO_DIGITS).doubleValue();
	}

	/**
	 * A time in nanoseconds since the epoch, to the second, as Python's
	 * {@code datetime.fromtimestamp(ns / 1e9, UTC).strftime(...)} writes it.
	 * @param nanoseconds the time
	 * @return the time, such as {@code 2026-10-01T00:00:59Z}
	 */
	static String ofNanoseconds(long nanoseconds) {
		double seconds = nanoseconds / NANOS_PER_SECOND;
		double whole = (seconds >= 0) ? Math.floor(seconds) : Math.ceil(seconds);
		double micros = Math.rint((seconds - whole) * MICROS_PER_SECOND);
		if (micros >= MICROS_PER_SECOND) {
			whole += 1;
		}
		else if (micros < 0) {
			whole -= 1;
		}
		return SECONDS.format(Instant.ofEpochSecond((long) whole));
	}

}
