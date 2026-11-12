package io.archsim.cli;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine.TypeConversionException;

import io.archsim.model.text.PythonText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A number option read as argparse's {@code type=float} read it, through Python's
 * {@code float()}: each text below was checked against Python 3.12.
 */
class PythonFloatTests {

	private final PythonFloat converter = new PythonFloat();

	@ParameterizedTest
	@CsvSource(delimiter = '|',
			value = { "60|60", "' 1_0 '|10", "1_000.000_1|1000.0001", "1e1_0|1e10", "1.|1", ".5|0.5", "+2|2", "-1|-1",
					"'\t60\n'|60", "'\u00a060\u3000'|60", "\u0661\u0662|12", "\uff13|3", "1E-3|0.001", "00012|12" })
	void convert_pythonsDecimal_isItsValue(String text, double value) {
		assertThat(this.converter.convert(text)).isEqualTo(value);
	}

	@ParameterizedTest
	@ValueSource(strings = { "inf", "+INF", "Infinity", "iNfInItY", "1e400", " infinity " })
	void convert_infinityOrAnExponentPastTheLargest_isPositiveInfinity(String text) {
		assertThat(this.converter.convert(text)).isEqualTo(Double.POSITIVE_INFINITY);
	}

	@ParameterizedTest
	@ValueSource(strings = { "-inf", "-Infinity", "-1e400" })
	void convert_negativeInfinity_isNegativeInfinity(String text) {
		assertThat(this.converter.convert(text)).isEqualTo(Double.NEGATIVE_INFINITY);
	}

	@ParameterizedTest
	@ValueSource(strings = { "nan", "NaN", "-nan", "+NAN" })
	void convert_nan_isNan(String text) {
		assertThat(this.converter.convert(text)).isNaN();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "0x10", "0x1p3", "1d", "1f", "1D", "_1", "1_", "1__0", "1_.5", "1._5", "1e_1",
			"in", "infinit", "infinityy", "nan1", "1e", "e5", ".", "--1", "+-1", "1 0", "1,5", "\u00bd", "1\u00b2",
			"Infinity1", "+", "1e+", "1.5.2", "\u001c60", "60\u001f" })
	void convert_textPythonsFloatRefuses_isATypeConversionError(String text) {
		assertThatExceptionOfType(TypeConversionException.class).isThrownBy(() -> this.converter.convert(text))
			.withMessage("invalid float value: " + PythonText.repr(text));
	}

}
