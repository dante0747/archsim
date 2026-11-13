package io.archsim.benchmarks.results;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the harness wrote something, as its files record it: UTC to the second.
 */
class TimestampsTests {

	@Test
	void now_aClockInAnotherZone_isUtcToTheSecond() {
		Clock clock = Clock.fixed(Instant.parse("2026-11-13T10:00:00.750Z"), ZoneId.of("Asia/Tehran"));

		String now = Timestamps.now(clock);

		assertThat(now).isEqualTo("2026-11-13T10:00:00Z");
	}

}
