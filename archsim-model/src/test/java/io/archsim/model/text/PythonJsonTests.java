package io.archsim.model.text;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
