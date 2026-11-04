package io.archsim.engine.numeric;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Python's {@code round(x, digits)}: the expected values are Python 3.12's. */
class PythonRoundTests {

	@ParameterizedTest(name = "round({0}, {1}) is {2}")
	@CsvSource(textBlock = """
			2.675, 2, 2.67
			0.125, 2, 0.12
			0.375, 2, 0.38
			1.23456789, 6, 1.234568
			2.5, 0, 2.0
			3.5, 0, 4.0
			1e22, 3, 1e22
			""")
	void round_aFloat_roundsItsExactValueHalfToEven(double value, int digits, double rounded) {
		assertThat(PythonRound.round(value, digits)).isEqualTo(rounded);
	}

	@Test
	void round_aSmallNegative_isNegativeZero() {
		assertThat(Math.copySign(1.0, PythonRound.round(-0.0001, 2))).isNegative();
	}

}
