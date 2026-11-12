package io.archsim.cli;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine.TypeConversionException;

import io.archsim.model.text.PythonText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A whole-number option read as argparse's {@code type=int} read it, through Python's
 * {@code int()}: each text below was checked against Python 3.12.
 */
class PythonIntTests {

	/** The most digits Python 3.12 converts from text to an integer. */
	private static final int MOST_DIGITS = 4300;

	private final PythonInt converter = new PythonInt();

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "7|7", "' 7 '|7", "1_0|10", "+5|5", "007|7", "-0|0", "-1|-1", "'\t7\n'|7",
			"'\u00a07\u3000'|7", "\u0663|3", "\uff17|7", "7\u0085|7", "99999999999999999999|99999999999999999999" })
	void convert_pythonsInteger_isItsValue(String text, BigInteger value) {
		assertThat(this.converter.convert(text)).isEqualTo(value);
	}

	@Test
	void convert_asManyDigitsAsPythonConverts_isItsValue() {
		String digits = "1".repeat(MOST_DIGITS);

		assertThat(this.converter.convert(digits)).isEqualTo(new BigInteger(digits));
	}

	@Test
	void convert_asManyDigitsAsPythonConvertsWithUnderscores_isItsValue() {
		String digits = "1_".repeat(MOST_DIGITS - 1) + "1";

		assertThat(this.converter.convert(digits)).isEqualTo(new BigInteger("1".repeat(MOST_DIGITS)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "1__0", "_1", "1_", "+_1", "+ 5", "--1", "0x10", "1e3", "1.0", "1.", "seven",
			"\u200b7", "\u00bd", "1\u00b2", "\u001c7" })
	void convert_textPythonsIntRefuses_isATypeConversionError(String text) {
		assertThatExceptionOfType(TypeConversionException.class).isThrownBy(() -> this.converter.convert(text))
			.withMessage("invalid int value: " + PythonText.repr(text));
	}

	@Test
	void convert_moreDigitsThanPythonConverts_isRefused() {
		String digits = "1_".repeat(MOST_DIGITS) + "1";

		assertThatExceptionOfType(TypeConversionException.class).isThrownBy(() -> this.converter.convert(digits))
			.withMessage("invalid int value: " + PythonText.repr(digits));
	}

	@Test
	void convert_moreDigitsThanPythonConvertsCountingLeadingZeros_isRefused() {
		String digits = "0".repeat(MOST_DIGITS) + "1";

		assertThatExceptionOfType(TypeConversionException.class).isThrownBy(() -> this.converter.convert(digits))
			.withMessage("invalid int value: " + PythonText.repr(digits));
	}

}
