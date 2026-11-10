package io.archsim.engine.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * JSON read as Python's {@code json.loads} reads it, refused in its words where no value
 * starts: the texts are Python 3.12's.
 */
class PythonJsonReaderTests {

	/** Deeper than the reader reads. */
	private static final int TOO_DEEP = 1001;

	/** U+FEFF, which Windows tools may write before the JSON. */
	private static final char BYTE_ORDER_MARK = 0xFEFF;

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "''|Expecting value: line 1 column 1 (char 0)",
			"'  x'|Expecting value: line 1 column 3 (char 2)", "'\n\n  ]'|Expecting value: line 3 column 3 (char 4)" })
	void read_textWhereNoValueStarts_isRefusedInJsonsWords(String text, String message) {
		assertThatExceptionOfType(JsonDecodeException.class).isThrownBy(() -> PythonJsonReader.read(text))
			.withMessage(message);
	}

	@Test
	void read_aByteOrderMark_isRefusedInJsonsWords() {
		assertThatExceptionOfType(JsonDecodeException.class)
			.isThrownBy(() -> PythonJsonReader.read(BYTE_ORDER_MARK + "{}"))
			.withMessage("Unexpected UTF-8 BOM (decode using utf-8-sig): line 1 column 1 (char 0)");
	}

	@Test
	void decode_aByteOrderMark_isNoValueAsJsonsDecoderFindsNone() {
		// json.loads(bytes) decodes the bytes and leaves out str's check for the mark
		assertThatExceptionOfType(JsonDecodeException.class)
			.isThrownBy(() -> PythonJsonReader.decode(BYTE_ORDER_MARK + "{}"))
			.withMessage("Expecting value: line 1 column 1 (char 0)");
	}

	@Test
	void decode_json_isItsValue() {
		JsonNode value = PythonJsonReader.decode(" {\"a\": [1, NaN]} ");

		assertThat(value.at("/a/0").intValue()).isEqualTo(1);
		assertThat(value.at("/a/1").doubleValue()).isNaN();
	}

	@Test
	void read_textCutShort_isRefusedInTheParsersWordsInJsonsForm() {
		assertThatExceptionOfType(JsonDecodeException.class).isThrownBy(() -> PythonJsonReader.read("[1, 2"))
			.withMessageEndingWith(": line 1 column 6 (char 5)");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|',
			value = { "'{\"a\": 1} x'|Extra data: line 1 column 10 (char 9)",
					"'[1]\n\n  2'|Extra data: line 3 column 3 (char 7)", "'1 2'|Extra data: line 1 column 3 (char 2)",
					"'\"a\"]'|Extra data: line 1 column 4 (char 3)" })
	void read_anythingAfterTheValue_isRefusedInJsonsWordsWhereItStarts(String text, String message) {
		assertThatExceptionOfType(JsonDecodeException.class).isThrownBy(() -> PythonJsonReader.read(text))
			.withMessage(message);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|',
			value = { "1x|Extra data: line 1 column 2 (char 1)", "truex|Extra data: line 1 column 5 (char 4)",
					"nullx|Extra data: line 1 column 5 (char 4)", "1.x|Extra data: line 1 column 2 (char 1)",
					"01|Extra data: line 1 column 2 (char 1)", "NaN1|Extra data: line 1 column 4 (char 3)",
					"-Infinityx|Extra data: line 1 column 10 (char 9)",
					"'true  x'|Extra data: line 1 column 7 (char 6)", "1e5x|Extra data: line 1 column 4 (char 3)",
					"-0x|Extra data: line 1 column 3 (char 2)" })
	void read_textRightAfterAWordOrNumber_isExtraDataWhereThePythonScannerStopped(String text, String message) {
		assertThatExceptionOfType(JsonDecodeException.class).isThrownBy(() -> PythonJsonReader.read(text))
			.withMessage(message);
	}

	@Test
	void read_spacesAfterTheValue_areRead() {
		assertThat(PythonJsonReader.read("[1] \r\n\t").toString()).isEqualTo("[1]");
	}

	@Test
	void read_nestingDeeperThanItReads_isRefusedAsTooDeepNotAsText() {
		String text = "[".repeat(TOO_DEEP) + "]".repeat(TOO_DEEP);

		assertThatExceptionOfType(JsonTooDeepException.class).isThrownBy(() -> PythonJsonReader.read(text))
			.withMessage("the JSON nests more than 1000 levels deep");
	}

	@Test
	void read_aNumberLongerThanItReads_isRefusedInTheParsersWordsWithoutItsApi() {
		// Python refused a whole number that long in its own words, with no place either
		assertThatExceptionOfType(JsonDecodeException.class)
			.isThrownBy(() -> PythonJsonReader.read("[" + "1".repeat(4301) + "]"))
			.withMessage("Number value length (4301) exceeds the maximum allowed (4300)");
	}

	@Test
	void read_nanAndTheInfinities_areNumbers() {
		JsonNode value = PythonJsonReader.read("[NaN, Infinity, -Infinity]");

		assertThat(value.valueStream().map(JsonNode::doubleValue)).containsExactly(Double.NaN, Double.POSITIVE_INFINITY,
				Double.NEGATIVE_INFINITY);
	}

	@Test
	void read_aKeyTwice_keepsItsFirstPlaceWithItsLastValue() {
		JsonNode value = PythonJsonReader.read("{\"a\": 1, \"b\": 2, \"a\": 3}");

		assertThat(value.toString()).isEqualTo("{\"a\":3,\"b\":2}");
	}

}
