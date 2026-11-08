package io.archsim.model.component.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** A runtime's pauses, as a run models them. */
class GcPausesTests {

	@Test
	void gcPauses_noPause_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> new GcPauses(0.0, 2000))
			.withMessage("a runtime's pauses take time and come every so many requests; got 0.0 ms every 2000");
	}

	@Test
	void gcPauses_noRequestsBetweenThem_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> new GcPauses(12.0, 0))
			.withMessage("a runtime's pauses take time and come every so many requests; got 12.0 ms every 0");
	}

}
