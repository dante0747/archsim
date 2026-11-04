package io.archsim.engine.text;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rounding with halves away from zero, on a float's exact value: Python 3.12's results.
 */
class HalfUpTests {

	@ParameterizedTest(name = "round({0}, {1}) is {2}")
	@CsvSource(textBlock = """
			2.5, 0, 3.0
			-2.5, 0, -3.0
			0.125, 2, 0.13
			2.675, 2, 2.67
			-12.345, 2, -12.35
			1e16, 3, 1e16
			""")
	void round_aFloat_roundsItsExactValueHalfAwayFromZero(double value, int digits, double rounded) {
		assertThat(HalfUp.round(value, digits)).isEqualTo(rounded);
	}

	@ParameterizedTest(name = "round({0}, 2) is -0.0")
	@CsvSource({ "-0.001", "-0.0" })
	void round_aSmallNegative_keepsTheSign(double value) {
		double rounded = HalfUp.round(value, 2);

		assertThat(Math.copySign(1.0, rounded)).isNegative();
	}

	@ParameterizedTest(name = "fixed({0}, {1}) is {2}")
	@CsvSource(textBlock = """
			1, 2, 1.00
			0.05, 1, 0.1
			2.675, 2, 2.67
			1e16, 1, 10000000000000000.0
			-0.001, 2, -0.00
			-1500, 0, -1500
			""")
	void fixed_aFloat_isItsRoundedDigits(double value, int digits, String text) {
		assertThat(HalfUp.fixed(value, digits)).isEqualTo(text);
	}

	@ParameterizedTest(name = "javaScriptRound({0}) is {1}")
	@CsvSource({ "2.5, 3.0", "-2.5, -2.0", "0.49999999999999994, 1.0" })
	void javaScriptRound_aFloat_roundsHalvesTowardsPositiveInfinity(double value, double rounded) {
		assertThat(HalfUp.javaScriptRound(value)).isEqualTo(rounded);
	}

	@ParameterizedTest(name = "grouped({0}) is {1}")
	@CsvSource(delimiter = '|', textBlock = """
			1234.5|1,235
			-0.4|0
			-0.5|-1
			1e16|10,000,000,000,000,000
			""")
	void grouped_aFloat_isTheNearestWholeNumberWithSeparators(double value, String text) {
		assertThat(HalfUp.grouped(value)).isEqualTo(text);
	}

}
