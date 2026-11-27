package io.archsim.model.distribution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A uniform distribution's own check holds for any number, NaN included: a record can be
 * made without the codec, and a comparison with NaN is false whichever way it asks.
 */
class UniformTests {

	@Test
	void new_aMaximumThatIsNotANumber_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> new Uniform(1, Double.NaN))
			.withMessage("uniform distribution requires max >= min");
	}

	@Test
	void new_aMinimumThatIsNotANumber_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> new Uniform(Double.NaN, 5))
			.withMessage("uniform distribution requires max >= min");
	}

}
