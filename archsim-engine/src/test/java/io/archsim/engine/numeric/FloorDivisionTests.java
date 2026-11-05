package io.archsim.engine.numeric;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Python's {@code //} on floats, with Python's results. */
class FloorDivisionTests {

	@Test
	void of_aQuotientJustUnderAWholeNumber_isTheWholeNumberBelow() {
		// 1 / 0.1 rounds to 10.0, but 0.1 is a little more than a tenth
		assertThat(FloorDivision.of(1, 0.1)).isEqualTo(9.0);
	}

	@Test
	void of_aNegativeQuotient_roundsDown() {
		assertThat(FloorDivision.of(-7, 2)).isEqualTo(-4.0);
		assertThat(FloorDivision.of(7, -2)).isEqualTo(-4.0);
	}

	@Test
	void of_aWholeQuotient_isExact() {
		assertThat(FloorDivision.of(6, 3)).isEqualTo(2.0);
		assertThat(FloorDivision.of(7.5, 2)).isEqualTo(3.0);
	}

	@Test
	void of_aZeroQuotient_hasTheSignOfTheTrueQuotient() {
		assertThat(Double.doubleToRawLongBits(FloorDivision.of(0, -3))).isEqualTo(Double.doubleToRawLongBits(-0.0));
		assertThat(Double.doubleToRawLongBits(FloorDivision.of(1, 3))).isEqualTo(Double.doubleToRawLongBits(0.0));
	}

	@Test
	void of_aDivisorOfZero_isAnError() {
		assertThatExceptionOfType(ArithmeticException.class).isThrownBy(() -> FloorDivision.of(1, 0))
			.withMessage("float floor division by zero");
	}

}
