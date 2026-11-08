package io.archsim.model.component.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** A runtime's warm-up, as a run models it. */
class WarmupTests {

	@Test
	void warmup_noSlowdown_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> new Warmup(1.0, 30_000.0))
			.withMessage("a warm-up slows a new instance for some time; got 1.0 for 30000.0 ms");
	}

	@Test
	void warmup_noTime_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> new Warmup(2.5, 0.0))
			.withMessage("a warm-up slows a new instance for some time; got 2.5 for 0.0 ms");
	}

	@Test
	void slowdownAfter_aJustStartedInstance_isTheFactor() {
		assertThat(new Warmup(2.5, 30_000.0).slowdownAfter(0.0)).isEqualTo(2.5);
	}

	@Test
	void slowdownAfter_halfTheWarmUp_isHalfTheExtraTime() {
		assertThat(new Warmup(2.5, 30_000.0).slowdownAfter(15_000.0)).isEqualTo(1.75);
	}

	@Test
	void slowdownAfter_theWholeWarmUp_isNone() {
		assertThat(new Warmup(2.5, 30_000.0).slowdownAfter(30_000.0)).isEqualTo(1.0);
	}

}
