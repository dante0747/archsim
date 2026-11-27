package io.archsim.model.distribution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A lognormal's own check holds for any number, NaN included: a record can be made
 * without the codec, and a comparison with NaN is false whichever way it asks.
 */
class LogNormalTests {

	@Test
	void new_aP95ThatIsNotANumber_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> new LogNormal(8, Double.NaN))
			.withMessage("lognormal distribution requires p95 >= median");
	}

	@Test
	void new_aMedianThatIsNotANumber_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> new LogNormal(Double.NaN, 25))
			.withMessage("lognormal distribution requires p95 >= median");
	}

}
