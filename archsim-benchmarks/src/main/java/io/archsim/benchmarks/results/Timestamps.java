package io.archsim.benchmarks.results;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * When the harness wrote something, as its files record it: UTC to the second,
 * {@code 2026-11-13T10:00:00Z}.
 */
public final class Timestamps {

	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
		.withZone(ZoneOffset.UTC);

	private Timestamps() {
	}

	/**
	 * The time now.
	 * @param clock the clock
	 * @return the time, in UTC to the second
	 */
	public static String now(Clock clock) {
		return FORMAT.format(clock.instant());
	}

}
