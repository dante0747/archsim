package io.archsim.cli;

import java.util.HexFormat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * JSON read from bytes as Python's {@code json.loads(bytes)} reads it, the baseline a
 * check reads at a git ref: its encoding found as {@code json.detect_encoding} finds it,
 * from a byte order mark or the zero bytes of the first four, and decoded with
 * {@code surrogatepass}. Each case was checked against Python 3.12.
 */
class PythonJsonBytesTests {

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "efbbbf7b7d|{}", "fffe7b007d00|{}", "feff007b007d|{}", "7b007d00|{}",
			"007b007d|{}", "0000007b0000007d|{}", "fffe00007b0000007d000000|{}", "7b0000007d000000|{}" })
	void read_jsonInAnyEncodingPythonFinds_isItsValue(String hex, String value) {
		assertThat(PythonJsonBytes.read(bytes(hex)).toString()).isEqualTo(value);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|',
			value = { "7b2261223a22eda080227d|d800", "fffe7b002200610022003a00220000dc22007d00|dc00",
					"7b0000002200000061000000220000003a0000002200000000d80000220000007d000000|d800" })
	void read_aLoneSurrogate_passesAsSurrogatepassLetsIt(String hex, String surrogate) {
		String text = PythonJsonBytes.read(bytes(hex))
			.propertyStream()
			.findFirst()
			.orElseThrow()
			.getValue()
			.stringValue();

		assertThat(text).isEqualTo(String.valueOf((char) Integer.parseInt(surrogate, 16)));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '"', value = {
			"fffe7b007d|'utf-16-le' codec can't decode byte 0x7d in position 4: truncated data",
			"7b007d0000|'utf-16-le' codec can't decode byte 0x00 in position 4: truncated data",
			"7b0000007d00000000|'utf-32-le' codec can't decode byte 0x00 in position 8: truncated data",
			"7b0000007d0000000000|'utf-32-le' codec can't decode bytes in position 8-9: truncated data",
			"7b00000000001100|'utf-32-le' codec can't decode bytes in position 4-7: code point not in range(0x110000)",
			"fffe000000001100|'utf-32-le' codec can't decode bytes in position 4-7: code point not in range(0x110000)",
			"0000007b00110000|'utf-32-be' codec can't decode bytes in position 4-7: code point not in range(0x110000)",
			"efbbbfff|'utf-8' codec can't decode byte 0xff in position 0: invalid start byte",
			"7b2261223a2022eda0227d|'utf-8' codec can't decode byte 0xed in position 7: invalid continuation byte",
			"7b7d00|Extra data: line 1 column 3 (char 2)", "fffe00d87b00|Expecting value: line 1 column 1 (char 0)",
			"efbbbfefbbbf7b7d|Expecting value: line 1 column 1 (char 0)",
			"fffefffe7b007d00|Expecting value: line 1 column 1 (char 0)",
			"7b2261223a22eda080ff227d|'utf-8' codec can't decode byte 0xff in position 9: invalid start byte",
			"efbbbf7b2261223a22eda080ff227d|'utf-8' codec can't decode byte 0xff in position 9: invalid start byte",
			"\"\"|Expecting value: line 1 column 1 (char 0)" })
	void read_bytesPythonRefuses_areRefusedInItsWords(String hex, String message) {
		assertThatExceptionOfType(PythonFileException.class).isThrownBy(() -> PythonJsonBytes.read(bytes(hex)))
			.withMessage(message);
	}

	private static byte[] bytes(String hex) {
		return HexFormat.of().parseHex(hex.replace(" ", ""));
	}

}
