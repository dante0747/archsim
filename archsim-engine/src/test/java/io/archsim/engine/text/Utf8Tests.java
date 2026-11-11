package io.archsim.engine.text;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Where bytes stop being UTF-8, in the words CPython 3.12's {@code bytes.decode("utf-8")}
 * raised, each checked against it.
 */
class Utf8Tests {

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
			# a byte that starts no character: a continuation, an overlong lead, past U+10FFFF
			80       | 'utf-8' codec can't decode byte 0x80 in position 0: invalid start byte
			c0af     | 'utf-8' codec can't decode byte 0xc0 in position 0: invalid start byte
			f5808080 | 'utf-8' codec can't decode byte 0xf5 in position 0: invalid start byte
			# below each lead's bound: overlong forms, a surrogate, past U+10FFFF
			e08080   | 'utf-8' codec can't decode byte 0xe0 in position 0: invalid continuation byte
			eda080   | 'utf-8' codec can't decode byte 0xed in position 0: invalid continuation byte
			f0808080 | 'utf-8' codec can't decode byte 0xf0 in position 0: invalid continuation byte
			f4908080 | 'utf-8' codec can't decode byte 0xf4 in position 0: invalid continuation byte
			c328     | 'utf-8' codec can't decode byte 0xc3 in position 0: invalid continuation byte
			# bytes that end inside a character
			e282     | 'utf-8' codec can't decode bytes in position 0-1: unexpected end of data
			61f09f98 | 'utf-8' codec can't decode bytes in position 1-3: unexpected end of data
			""")
	void firstError_bytesThatAreNotUtf8_areDescribedInCPythonsWords(String hex, String words) {
		assertThat(Utf8.firstError(HexFormat.of().parseHex(hex)))
			.hasValueSatisfying((error) -> assertThat(error.describe(Utf8.CODEC)).isEqualTo(words));
	}

	@Test
	void firstError_utf8OfEveryLength_isEmpty() {
		assertThat(Utf8.firstError("h\u00e9llo \u20ac \ud83d\ude00".getBytes(StandardCharsets.UTF_8))).isEmpty();
	}

	@Test
	void firstError_fromAPlace_countsPositionsFromTheFirstByte() {
		byte[] bytes = HexFormat.of().parseHex("ff6162ff");

		assertThat(Utf8.firstError(bytes, 1)).hasValueSatisfying((error) -> assertThat(error.describe(Utf8.CODEC))
			.isEqualTo("'utf-8' codec can't decode byte 0xff in position 3: invalid start byte"));
	}

	@Test
	void firstError_fromTheEnd_isEmpty() {
		byte[] bytes = HexFormat.of().parseHex("ff");

		assertThat(Utf8.firstError(bytes, bytes.length)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(ints = { -1, 3 })
	void firstError_aPlaceOutsideTheBytes_isRefused(int from) {
		assertThatExceptionOfType(IndexOutOfBoundsException.class).isThrownBy(() -> Utf8.firstError(new byte[2], from));
	}

}
