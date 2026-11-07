package io.archsim.engine.numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Numbers read from text as Python's {@code float()} and {@code int()} read them.
 */
class PythonNumbersTests {

	@Test
	void parseFloat_underscoresAndSpaces_areRead() {
		assertThat(PythonNumbers.parseFloat(" 1_000.5 ")).isEqualTo(1000.5);
	}

	@Test
	void parseFloat_digitsOfAnotherScript_areRead() {
		assertThat(PythonNumbers.parseFloat("١٢")).isEqualTo(12.0);
	}

	/**
	 * CPython strips a number's text of ASCII whitespace and of the spaces beyond ASCII
	 * that {@code str.isspace()} accepts, which Java's {@code strip()} does not all
	 * strip.
	 */
	@ParameterizedTest(name = "U+{0} around a number is stripped", quoteTextArguments = false)
	@ValueSource(strings = { "000B", "000C", "0085", "00A0", "1680", "2007", "2028", "202F", "3000" })
	void parseFloat_spaceAround_isStripped(String hex) {
		String space = Character.toString(HexFormat.fromHexDigits(hex));

		assertThat(PythonNumbers.parseFloat(space + "1.5" + space)).isEqualTo(1.5);
	}

	@ParameterizedTest(name = "U+{0} around a whole number is stripped", quoteTextArguments = false)
	@ValueSource(strings = { "000B", "0085", "00A0", "205F", "3000" })
	void parseInt_spaceAround_isStripped(String hex) {
		String space = Character.toString(HexFormat.fromHexDigits(hex));

		assertThat(PythonNumbers.parseInt(space + "f" + space, 16)).isEqualTo(BigInteger.valueOf(15));
	}

	/**
	 * The information separators are whitespace to Java's {@code strip()}, not to
	 * CPython's numbers.
	 */
	@Test
	void parseFloat_unitSeparatorAfterTheNumber_isRefusedInPythonsWords() {
		assertThatExceptionOfType(NumberFormatException.class)
			.isThrownBy(() -> PythonNumbers.parseFloat("1" + Character.toString(0x1F)))
			.withMessage("could not convert string to float: '1\\x1f'");
	}

	@Test
	void parseInt_fileSeparatorBeforeTheNumber_isRefusedInPythonsWords() {
		assertThatExceptionOfType(NumberFormatException.class)
			.isThrownBy(() -> PythonNumbers.parseInt(Character.toString(0x1C) + "1"))
			.withMessage("invalid literal for int() with base 10: '\\x1c1'");
	}

	@Test
	void parseFloat_infinityInAnyCase_isInfinite() {
		assertThat(PythonNumbers.parseFloat("-Infinity")).isEqualTo(Double.NEGATIVE_INFINITY);
	}

	@Test
	void parseFloat_textThatIsNoNumber_isRefusedInPythonsWords() {
		assertThatExceptionOfType(NumberFormatException.class).isThrownBy(() -> PythonNumbers.parseFloat("1.2.3"))
			.withMessage("could not convert string to float: '1.2.3'");
	}

	@Test
	void parseInt_aFloatsText_isRefusedInPythonsWords() {
		assertThatExceptionOfType(NumberFormatException.class).isThrownBy(() -> PythonNumbers.parseInt("3.5"))
			.withMessage("invalid literal for int() with base 10: '3.5'");
	}

	/** CPython words the refusal with {@code %.200R}: the repr's first 200 characters. */
	@Test
	void parseInt_longText_isQuotedToTwoHundredCharacters() {
		assertThatExceptionOfType(NumberFormatException.class)
			.isThrownBy(() -> PythonNumbers.parseInt("  " + "x".repeat(300)))
			.withMessage("invalid literal for int() with base 10: '  " + "x".repeat(197));
	}

	@Test
	void parseInt_longTextOfEscapes_isCutInsideAnEscape() {
		assertThatExceptionOfType(NumberFormatException.class)
			.isThrownBy(() -> PythonNumbers.parseInt("\0".repeat(100)))
			.withMessage("invalid literal for int() with base 10: '" + "\\x00".repeat(49) + "\\x0");
	}

	@Test
	void parseInt_longTextBeyondTheBasicPlane_isCutByCharactersNotUtf16Units() {
		String emoji = Character.toString(0x1F600);

		assertThatExceptionOfType(NumberFormatException.class)
			.isThrownBy(() -> PythonNumbers.parseInt(emoji.repeat(300)))
			.withMessage("invalid literal for int() with base 10: '" + emoji.repeat(199));
	}

	/** CPython words this refusal with {@code %R}: the whole repr. */
	@Test
	void parseFloat_longText_isQuotedWhole() {
		assertThatExceptionOfType(NumberFormatException.class)
			.isThrownBy(() -> PythonNumbers.parseFloat("x".repeat(300)))
			.withMessage("could not convert string to float: '" + "x".repeat(300) + "'");
	}

	@Test
	void parseInt_underscoresAndSpaces_areRead() {
		assertThat(PythonNumbers.parseInt(" 1_000 ")).isEqualTo(BigInteger.valueOf(1000));
	}

	@Test
	void parseInt_digitsOfAnotherScript_areRead() {
		assertThat(PythonNumbers.parseInt("١٢")).isEqualTo(BigInteger.valueOf(12));
		assertThat(PythonNumbers.parseInt("١", 16)).isEqualTo(BigInteger.ONE);
	}

	@Test
	void parseInt_lettersOfAnotherScript_areRefusedInPythonsWords() {
		assertThatExceptionOfType(NumberFormatException.class).isThrownBy(() -> PythonNumbers.parseInt("Ａ", 16))
			.withMessage("invalid literal for int() with base 16: 'Ａ'");
	}

	@Test
	void parseInt_inABase_readsItsDigitsWithOrWithoutItsPrefix() {
		assertThat(PythonNumbers.parseInt("1F", 16)).isEqualTo(BigInteger.valueOf(31));
		assertThat(PythonNumbers.parseInt("0x_1f", 16)).isEqualTo(BigInteger.valueOf(31));
		assertThat(PythonNumbers.parseInt("-0b101", 2)).isEqualTo(BigInteger.valueOf(-5));
		assertThat(PythonNumbers.parseInt("+0O17", 8)).isEqualTo(BigInteger.valueOf(15));
	}

	@Test
	void parseInt_digitOutsideTheBase_isRefusedInPythonsWords() {
		assertThatExceptionOfType(NumberFormatException.class).isThrownBy(() -> PythonNumbers.parseInt("09", 8))
			.withMessage("invalid literal for int() with base 8: '09'");
	}

	@Test
	void parseInt_prefixAlone_isRefusedInPythonsWords() {
		assertThatExceptionOfType(NumberFormatException.class).isThrownBy(() -> PythonNumbers.parseInt("0x", 16))
			.withMessage("invalid literal for int() with base 16: '0x'");
	}

	@Test
	void parseInt_pythonsMostDigits_areRead() {
		assertThat(PythonNumbers.parseInt("0" + "9".repeat(4299)).toString()).hasSize(4299);
	}

	@Test
	void parseInt_moreDigitsThanPythonConverts_isRefusedInPythonsWords() {
		assertThatExceptionOfType(NumberFormatException.class)
			.isThrownBy(() -> PythonNumbers.parseInt("-" + "1".repeat(4300) + "_1"))
			.withMessage("Exceeds the limit (4300 digits) for integer string conversion: value has 4301 digits; "
					+ "use sys.set_int_max_str_digits() to increase the limit");
	}

	@Test
	void parseInt_hexOfAHundredThousandDigits_isReadWithoutALimit() {
		assertThat(PythonNumbers.parseInt("f".repeat(100_000), 16).bitLength()).isEqualTo(400_000);
	}

	@Test
	void parseInt_binaryAndOctal_areReadWithoutALimit() {
		assertThat(PythonNumbers.parseInt("0b1_0110", 2)).isEqualTo(BigInteger.valueOf(22));
		assertThat(PythonNumbers.parseInt("1777", 8)).isEqualTo(BigInteger.valueOf(1023));
	}

	@Test
	void parseInt_doubleUnderscore_isRefused() {
		assertThatExceptionOfType(NumberFormatException.class).isThrownBy(() -> PythonNumbers.parseInt("1__0"))
			.withMessage("invalid literal for int() with base 10: '1__0'");
	}

	@Test
	void round_halves_goToEven() {
		assertThat(PythonNumbers.round(2.5)).isEqualTo(BigInteger.TWO);
		assertThat(PythonNumbers.round(3.5)).isEqualTo(BigInteger.valueOf(4));
	}

	@Test
	void round_nan_isRefusedInPythonsWords() {
		assertThatExceptionOfType(NumberFormatException.class).isThrownBy(() -> PythonNumbers.round(Double.NaN))
			.withMessage("cannot convert float NaN to integer");
	}

	@Test
	void toFloat_wholeNumberBeyondAFloat_isRefusedInPythonsWords() {
		assertThatExceptionOfType(NumberFormatException.class)
			.isThrownBy(() -> PythonNumbers.toFloat(BigInteger.TEN.pow(400)))
			.withMessage("int too large to convert to float");
	}

	@Test
	void divide_wholeNumbers_isTheExactQuotientsNearestFloat() {
		assertThat(PythonNumbers.divide(BigInteger.valueOf(7), BigDecimal.valueOf(100))).isEqualTo(0.07);
	}

	@Test
	void divide_quotientBeyondAFloat_isRefusedInPythonsWords() {
		assertThatExceptionOfType(NumberFormatException.class)
			.isThrownBy(() -> PythonNumbers.divide(BigInteger.TEN.pow(400), BigDecimal.valueOf(1024)))
			.withMessage("integer division result too large for a float");
	}

	@Test
	void truncate_negativeFloat_goesTowardZero() {
		assertThat(PythonNumbers.truncate(-2.7)).isEqualTo(BigInteger.valueOf(-2));
	}

	@Test
	void truncate_infinity_isRefusedInPythonsWords() {
		assertThatExceptionOfType(NumberFormatException.class)
			.isThrownBy(() -> PythonNumbers.truncate(Double.POSITIVE_INFINITY))
			.withMessage("cannot convert float infinity to integer");
	}

}
