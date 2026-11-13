package io.archsim.model.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Python's {@code json.dumps} with its keys in their order: the texts are Python 3.12's.
 */
class PythonJsonTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void write_object_keepsTheKeysInTheirOrder() {
		JsonNode value = JSON.readTree("{\"b\": 1, \"a\": [1, 2.5, 1e-05, -0.0, true, null]}");

		String text = PythonJson.write(value);

		assertThat(text).isEqualTo("{\"b\":1,\"a\":[1,2.5,1e-05,-0.0,true,null]}");
	}

	@Test
	void write_nonAsciiText_escapesEachUtf16Unit() {
		JsonNode value = JSON.readTree("{\"u\": \"café 😀\"}");

		String text = PythonJson.write(value);

		assertThat(text).isEqualTo("{\"u\":\"caf\\u00e9 \\ud83d\\ude00\"}");
	}

	@Test
	void writeIndented_nestedValues_putsEachItemOnALineOfItsOwn() {
		JsonNode value = JSON.readTree("{\"b\": 1, \"a\": [1, 2.5, {}, [], {\"c\": \"café\"}], \"d\": {}}");

		String text = PythonJson.writeIndented(value, 2);

		assertThat(text).isEqualTo("{\n  \"b\": 1,\n  \"a\": [\n    1,\n    2.5,\n    {},\n    [],\n    {\n"
				+ "      \"c\": \"caf\\u00e9\"\n    }\n  ],\n  \"d\": {}\n}");
	}

	@Test
	void writeIndentedAsIs_textBeyondAscii_isWrittenAsItIsAndFloatsAsTheirRepr() {
		// as json.dumps(value, indent=2, ensure_ascii=False) writes accuracy.json
		JsonNode value = JSON.readTree("{\"a\": \"±22% \\u2192 😀\", \"b\": [1e-05, \"\\n\\u0001\"]}");

		String text = PythonJson.writeIndentedAsIs(value, 2);

		assertThat(text).isEqualTo("{\n  \"a\": \"±22% → 😀\",\n  \"b\": [\n    1e-05,\n    \"\\n\\u0001\"\n  ]\n}");
	}

	@Test
	void writePydantic_textBeyondAscii_isWrittenAsItIs() {
		// as pydantic_core.to_json(value, indent=2) wrote the MCP server's answers
		JsonNode value = JSON.readTree("{\"a\": \"caf\\u00e9 \\u2192 \\ud83d\\ude00\", \"b\": [\"\\n\\u0001\"]}");

		String text = PythonJson.writePydantic(value, 2);

		assertThat(text).isEqualTo("{\n  \"a\": \"café → 😀\",\n  \"b\": [\n    \"\\n\\u0001\"\n  ]\n}");
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', textBlock = """
			# as pydantic-core 2.50.2's to_json wrote each: Python's repr, but for an exponent
			# of -5 written out, and lower ones without their zero
			1e-05                   | 0.00001
			2.345e-05               | 0.00002345
			-1e-05                  | -0.00001
			9.999e-05               | 0.00009999
			3.0000000000000004e-05  | 0.000030000000000000004
			9.99e-06                | 9.99e-6
			-1.5e-07                | -1.5e-7
			1e-100                  | 1e-100
			0.0001                  | 0.0001
			0.0                     | 0.0
			1e+16                   | 1e+16
			1.7976931348623157e+308 | 1.7976931348623157e+308
			NaN                     | NaN
			-Infinity               | -Infinity
			""")
	void writePydantic_aFloat_isWrittenAsPydanticCoreWroteIt(double value, String text) {
		assertThat(PythonJson.writePydantic(JsonNodeFactory.instance.numberNode(value), 2)).isEqualTo(text);
	}

	@Test
	void writeIndented_aFloatBelowOneTenThousandth_isItsRepr() {
		// json.dumps, unlike pydantic_core, writes Python's repr
		String text = PythonJson.writeIndented(JsonNodeFactory.instance.numberNode(2.345e-05), 2);

		assertThat(text).isEqualTo("2.345e-05");
	}

	@Test
	void writeIndented_oneSpace_indentsEachLevelByOneSpace() {
		JsonNode value = JSON.readTree("{\"x\": [[]]}");

		String text = PythonJson.writeIndented(value, 1);

		assertThat(text).isEqualTo("{\n \"x\": [\n  []\n ]\n}");
	}

	@Test
	void writeIndented_anEmptyArray_isWrittenOnOneLine() {
		String text = PythonJson.writeIndented(JSON.readTree("[]"), 2);

		assertThat(text).isEqualTo("[]");
	}

	@Test
	void quote_textWithQuotesAndControlCharacters_isEscapedAsJson() {
		String text = PythonJson.quote("say \"hi\"\t\\ \u0001");

		assertThat(text).isEqualTo("\"say \\\"hi\\\"\\t\\\\ \\u0001\"");
	}

	@Test
	void quote_nonAsciiText_keepsTheCharacters() {
		String text = PythonJson.quote("naïve \u007f");

		assertThat(text).isEqualTo("\"naïve \u007f\"");
	}

}
