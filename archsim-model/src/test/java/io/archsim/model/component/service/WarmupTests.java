package io.archsim.model.component.service;

import org.junit.jupiter.api.Test;

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

}
