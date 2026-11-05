package io.archsim.engine.numeric;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Python's {@code /} on floats. */
class TrueDivisionTests {

	@Test
	void of_aDivisor_isTheQuotient() {
		assertThat(TrueDivision.of(1, 4)).isEqualTo(0.25);
	}

	@Test
	void of_aDivisorOfZero_isAnError() {
		assertThatExceptionOfType(ArithmeticException.class).isThrownBy(() -> TrueDivision.of(1, 0))
			.withMessage("float division by zero");
	}

	@Test
	void of_aDivisorOfNegativeZero_isAnError() {
		assertThatExceptionOfType(ArithmeticException.class).isThrownBy(() -> TrueDivision.of(0, -0.0));
	}

}
