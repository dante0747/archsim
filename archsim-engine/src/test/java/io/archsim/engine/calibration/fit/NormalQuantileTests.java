package io.archsim.engine.calibration.fit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.withinPercentage;

/**
 * The standard normal distribution's inverse, equal to what Python's
 * {@code NormalDist().inv_cdf} gives in each of its three ranges.
 */
class NormalQuantileTests {

	/** A relative 1e-15, as a percentage. */
	private static final double TAIL_TOLERANCE_PERCENT = 1e-13;

	@ParameterizedTest
	@CsvSource({ "0.5, 0.0", "0.75, 0.6744897501960817" })
	void of_shareInTheCentralRange_isPythonsInverseExactly(double share, double expected) {
		assertThat(NormalQuantile.of(share)).isEqualTo(expected);
	}

	// a tail goes through a logarithm, StrictMath's here and the C library's in Python,
	// which may part in the last places (ADR-0050)
	@ParameterizedTest
	@CsvSource({ "0.99, 2.3263478740408408", "0.01, -2.3263478740408408", "0.999999999999, 7.0344869100478356",
			"1e-300, -37.0470962993612" })
	void of_shareInATail_isPythonsInverseToALogarithmsLastPlaces(double share, double expected) {
		assertThat(NormalQuantile.of(share)).isCloseTo(expected, withinPercentage(TAIL_TOLERANCE_PERCENT));
	}

	@ParameterizedTest
	@ValueSource(doubles = { 0.0, 1.0, -0.5 })
	void of_shareNotStrictlyBetweenZeroAndOne_isRefusedInPythonsWords(double share) {
		assertThatIllegalArgumentException().isThrownBy(() -> NormalQuantile.of(share))
			.withMessage("p must be in the range 0.0 < p < 1.0");
	}

	@Test
	void of_nan_isNaNAsPythonGivesIt() {
		assertThat(NormalQuantile.of(Double.NaN)).isNaN();
	}

}
