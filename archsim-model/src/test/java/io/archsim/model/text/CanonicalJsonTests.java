package io.archsim.model.text;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Python's {@code json.dumps} with sorted keys and no spaces: the texts are Python
 * 3.12's.
 */
class CanonicalJsonTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void write_nonAsciiText_keepsTheCharacters() {
		JsonNode value = JSON.readTree("{\"u\": \"café ✓ 😀\"}");

		String text = CanonicalJson.write(value);

		assertThat(text).isEqualTo("{\"u\":\"café ✓ 😀\"}");
	}

	@Test
	void writeAscii_nonAsciiText_escapesEachUtf16Unit() {
		JsonNode value = JSON.readTree("{\"u\": \"café ✓ 😀\"}");

		String text = CanonicalJson.writeAscii(value);

		assertThat(text).isEqualTo("{\"u\":\"caf\\u00e9 \\u2713 \\ud83d\\ude00\"}");
	}

	@Test
	void writeAscii_deleteCharacter_isEscaped() {
		JsonNode value = JSON.readTree("[\"a\\u007fb\"]");

		String text = CanonicalJson.writeAscii(value);

		assertThat(text).isEqualTo("[\"a\\u007fb\"]");
	}

	@Test
	void writeAscii_keysAndNumbers_areWrittenAsWriteWritesThem() {
		JsonNode value = JSON.readTree("{\"b\": 1, \"a\": [1, 2.5, 1e-05, -0.0, 9223372036854775808]}");

		String text = CanonicalJson.writeAscii(value);

		assertThat(text).isEqualTo("{\"a\":[1,2.5,1e-05,-0.0,9223372036854775808],\"b\":1}");
	}

}
