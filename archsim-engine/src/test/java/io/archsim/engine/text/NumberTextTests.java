package io.archsim.engine.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** How the engine writes numbers in its texts: Python 3.12's results. */
class NumberTextTests {

	@ParameterizedTest(name = "plain({0}) is {1}")
	@CsvSource(delimiter = '|', textBlock = """
			12.0|12
			-0.0|0
			12.5|12.5
			0.00001|1e-05
			1e16|10000000000000000
			""")
	void plain_aFloat_isWrittenWithoutAPointWhenWhole(double value, String text) {
		assertThat(NumberText.plain(value)).isEqualTo(text);
	}

	@ParameterizedTest(name = "formula({0}, {1}) is {2}")
	@CsvSource(delimiter = '|', textBlock = """
			1234.5|1|1,234
			12.345|1|12.3
			2.675|1|2.67
			0.5|2|0.5
			0.0001234|1|0
			-1500|1|-1,500
			""")
	void formula_aFloat_hasSeparatorsAndNoTrailingZeros(double value, int digits, String text) {
		assertThat(NumberText.formula(value, digits)).isEqualTo(text);
	}

	@Test
	void formula_noValue_isADash() {
		assertThat(NumberText.formula(null, 1)).isEqualTo("–");
	}

	@Test
	void formula_aValueThatIsNotFinite_isADash() {
		assertThat(NumberText.formula(Double.NaN, 1)).isEqualTo("–");
	}

	@ParameterizedTest(name = "percent({0}) is {1}")
	@CsvSource(delimiter = '|', textBlock = """
			0.0125|1.2%
			0.5|50%
			0.9995|100%
			""")
	void percent_aFraction_hasOneDecimalUnlessWhole(double fraction, String text) {
		assertThat(NumberText.percent(fraction)).isEqualTo(text);
	}

	@Test
	void percent_noValue_isADash() {
		assertThat(NumberText.percent(null)).isEqualTo("–");
	}

	@ParameterizedTest(name = "brief({0}) is {1}")
	@CsvSource(delimiter = '|', textBlock = """
			1234.5|1,235
			12.345|12.3
			0.125|0.13
			-0.5|-0.5
			Infinity|–
			""")
	void brief_aFloat_hasFewerDecimalsTheLargerItIs(double value, String text) {
		assertThat(NumberText.brief(value)).isEqualTo(text);
	}

	@ParameterizedTest(name = "milliseconds({0}) is {1}")
	@CsvSource(delimiter = '|', textBlock = """
			0.05|0.1
			9.95|10.0
			12.345|12
			999.5|1000
			""")
	void milliseconds_aLatency_hasOneDecimalUnderTen(double value, String text) {
		assertThat(NumberText.milliseconds(value)).isEqualTo(text);
	}

}
