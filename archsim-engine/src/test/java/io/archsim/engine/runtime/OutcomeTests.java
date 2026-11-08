package io.archsim.engine.runtime;

import org.junit.jupiter.api.Test;

import io.archsim.engine.report.FailureReason;

import static org.assertj.core.api.Assertions.assertThat;

/** How a request ended at a part. */
class OutcomeTests {

	@Test
	void ok_aCacheAsideMiss_isServed() {
		assertThat(Outcome.MISS.ok()).isTrue();
	}

	@Test
	void missed_aCacheAsideMiss_isTrue() {
		assertThat(Outcome.MISS.missed()).isTrue();
	}

	@Test
	void missed_aServedRequest_isFalse() {
		assertThat(Outcome.OK.missed()).isFalse();
	}

	@Test
	void ok_aRefusal_isFalse() {
		Outcome refused = Outcome.rejected(FailureReason.QUEUE_FULL, "cache");

		assertThat(refused.ok()).isFalse();
	}

}
