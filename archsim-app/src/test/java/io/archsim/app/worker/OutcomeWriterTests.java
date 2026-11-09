package io.archsim.app.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.times;
import static org.mockito.Mockito.mock;

/**
 * An outcome written through a brief database outage, as the Python worker wrote it:
 * tried three times, a second and then two apart, and given up after the third.
 */
class OutcomeWriterTests {

	private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

	private static final RunOutcome CANCELLED = new RunOutcome.Cancelled();

	private final LeaseRepository leases = mock();

	private final List<Duration> pauses = new ArrayList<>();

	private final OutcomeWriter writer = new OutcomeWriter(this.leases, "a", Clock.fixed(NOW, ZoneOffset.UTC),
			this.pauses::add);

	@Test
	void finish_aDatabaseThatFailsTwice_isTriedASecondThenTwoLater() {
		given(this.leases.finish("r", "a", CANCELLED, NOW)).willThrow(new DataAccessResourceFailureException("down"))
			.willThrow(new DataAccessResourceFailureException("down"))
			.willReturn(true);

		this.writer.finish("r", CANCELLED);

		then(this.leases).should(times(3)).finish("r", "a", CANCELLED, NOW);
		assertThat(this.pauses).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
	}

	@Test
	void finish_aDatabaseThatStaysDown_isGivenUpAfterThreeTries() {
		given(this.leases.finish(any(), any(), any(), any())).willThrow(new DataAccessResourceFailureException("down"));

		assertThatNoException().isThrownBy(() -> this.writer.finish("r", CANCELLED));
		then(this.leases).should(times(3)).finish(eq("r"), eq("a"), any(), any());
	}

}
