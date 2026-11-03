package io.archsim.app.web;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Points in time, written as pydantic writes a UTC datetime. */
class PydanticTimesTests {

	@Test
	void format_withMicroseconds_writesSixDigits() {
		String text = PydanticTimes.format(Instant.parse("2026-11-03T01:02:03.120Z"));

		assertThat(text).isEqualTo("2026-11-03T01:02:03.120000Z");
	}

	@Test
	void format_wholeSecond_writesNoFraction() {
		String text = PydanticTimes.format(Instant.parse("2026-11-03T01:02:03Z"));

		assertThat(text).isEqualTo("2026-11-03T01:02:03Z");
	}

	@Test
	void format_nanoseconds_areCutToMicroseconds() {
		String text = PydanticTimes.format(Instant.parse("2026-11-03T01:02:03.000000999Z"));

		assertThat(text).isEqualTo("2026-11-03T01:02:03Z");
	}

}
