package io.archsim.engine.report;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a reason, as a report names it, is one of the simulator's.
 */
class FailureReasonTests {

	@Test
	void is_itsOwnName_isTrue() {
		assertThat(FailureReason.QUEUE_FULL.is("queue_full")).isTrue();
	}

	@Test
	void is_anotherReason_isFalse() {
		assertThat(FailureReason.QUEUE_FULL.is("queue_timeout")).isFalse();
	}

	@Test
	void is_noReason_isFalse() {
		assertThat(FailureReason.OUTAGE.is(null)).isFalse();
	}

}
