package io.archsim.engine.numeric;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

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
	void parseInt_doubleUnderscore_isRefused() {
		assertThatExceptionOfType(NumberFormatException.class).isThrownBy(() -> PythonNumbers.parseInt("1__0"))
			.withMessage("invalid literal for int() with base 10: '1__0'");
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
