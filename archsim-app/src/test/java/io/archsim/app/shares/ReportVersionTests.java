package io.archsim.app.shares;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The report version a share link keeps: the string the engine writes, else unknown.
 */
class ReportVersionTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void reportVersion_string_isTheString() {
		assertThat(ShareLinks.reportVersion(json("{\"reportVersion\": \"1.14\"}"))).isEqualTo("1.14");
	}

	@Test
	void reportVersion_ofAnotherType_isUnknown() {
		assertThat(ShareLinks.reportVersion(json("{\"reportVersion\": 1.5}"))).isEqualTo("unknown");
	}

	@Test
	void reportVersion_empty_isUnknown() {
		assertThat(ShareLinks.reportVersion(json("{\"reportVersion\": \"\"}"))).isEqualTo("unknown");
	}

	@Test
	void reportVersion_missing_isUnknown() {
		assertThat(ShareLinks.reportVersion(json("{}"))).isEqualTo("unknown");
	}

	private static JsonNode json(String text) {
		return JSON.readTree(text);
	}

}
