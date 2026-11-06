package io.archsim.app.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.archsim.app.store.Times;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application's clock: UTC, and never the same stored time twice, so rows made one
 * after another sort in the order they were made.
 */
class ClockConfigurationTests {

	/** More readings than one tick of the coarsest system clock holds. */
	private static final int READINGS = 10_000;

	private final Clock clock = new ClockConfiguration().clock();

	@Test
	void clock_always_tellsUtc() {
		assertThat(this.clock.getZone()).isEqualTo(ZoneOffset.UTC);
	}

	@Test
	void clock_readOverAndOverAsTimesAreStored_neverRepeatsAMicrosecond() {
		List<Instant> readings = Stream.generate(() -> Times.now(this.clock)).limit(READINGS).toList();

		assertThat(readings).isSorted().doesNotHaveDuplicates();
	}

}
