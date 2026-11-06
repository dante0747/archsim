package io.archsim.app.store;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A clock whose instants never repeat and never go back, to the microsecond, whatever its
 * source tells.
 */
class StrictlyIncreasingClockTests {

	private static final Instant NINE = Instant.parse("2026-11-06T09:00:00Z");

	private static final Instant NINE_AND_A_MICROSECOND = NINE.plus(1, ChronoUnit.MICROS);

	private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

	@Test
	void instant_firstReading_isTheSourcesTimeToTheMicrosecond() {
		Clock clock = new StrictlyIncreasingClock(Clock.fixed(NINE.plusNanos(1_500), ZoneOffset.UTC));

		assertThat(clock.instant()).isEqualTo(NINE_AND_A_MICROSECOND);
	}

	@Test
	void instant_twiceWithinOneTickOfTheSource_movesOnByAMicrosecond() {
		Clock clock = new StrictlyIncreasingClock(Clock.fixed(NINE, ZoneOffset.UTC));

		assertThat(List.of(clock.instant(), clock.instant())).containsExactly(NINE, NINE_AND_A_MICROSECOND);
	}

	@Test
	void instant_afterTheSourceMovesOn_isTheSourcesTimeAgain() {
		Instant later = NINE.plusMillis(1);
		Clock clock = new StrictlyIncreasingClock(new Readings(NINE, NINE, later));

		assertThat(List.of(clock.instant(), clock.instant(), clock.instant())).containsExactly(NINE,
				NINE_AND_A_MICROSECOND, later);
	}

	@Test
	void instant_afterTheSourceGoesBack_staysAhead() {
		Clock clock = new StrictlyIncreasingClock(new Readings(NINE, NINE.minusSeconds(1)));

		assertThat(List.of(clock.instant(), clock.instant())).containsExactly(NINE, NINE_AND_A_MICROSECOND);
	}

	@Test
	void withZone_anotherZone_tellsThatZone() {
		Clock clock = new StrictlyIncreasingClock(Clock.fixed(NINE, ZoneOffset.UTC));

		assertThat(clock.withZone(PARIS).getZone()).isEqualTo(PARIS);
	}

	@Test
	void withZone_anotherZone_carriesOnFromTheSameLastInstant() {
		Clock clock = new StrictlyIncreasingClock(Clock.fixed(NINE, ZoneOffset.UTC));

		Clock inParis = clock.withZone(PARIS);

		assertThat(List.of(clock.instant(), inParis.instant(), clock.instant())).containsExactly(NINE,
				NINE_AND_A_MICROSECOND, NINE.plus(2, ChronoUnit.MICROS));
	}

	@Test
	void instant_readFromManyThreadsAtOnce_neverRepeats() {
		Clock clock = new StrictlyIncreasingClock(Clock.fixed(NINE, ZoneOffset.UTC));
		int readings = 50_000;

		Set<Instant> told = IntStream.range(0, readings)
			.parallel()
			.mapToObj((reading) -> clock.instant())
			.collect(Collectors.toSet());

		assertThat(told).hasSize(readings);
	}

	/**
	 * A source clock that tells the given times, one per reading.
	 */
	private static final class Readings extends Clock {

		private final Iterator<Instant> times;

		Readings(Instant... times) {
			this.times = List.of(times).iterator();
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException("The readings are in UTC");
		}

		@Override
		public Instant instant() {
			return this.times.next();
		}

	}

}
