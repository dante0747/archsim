package io.archsim.app.shares;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a share link keeps of a run.
 */
class SharePayloadsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void hideAddresses_calibrationSource_losesEveryAddress() {
		JsonNode hidden = SharePayloads.hideAddresses(
				json("""
						{"a": [{"calibration": {"source": "Prometheus https://user:pass@prom.internal:9090 and HTTP://x.y"}}]}"""));

		assertThat(hidden).isEqualTo(json("""
				{"a": [{"calibration": {"source": "Prometheus (address hidden) and (address hidden)"}}]}"""));
	}

	@Test
	void hideAddresses_addressRightAfterALetterOfAnotherScript_isHiddenToo() {
		JsonNode hidden = SharePayloads.hideAddresses(
				json("{\"calibration\": {\"source\": \"\u76e3\u8996https://user:pass@prom.internal:9090\"}}"));

		assertThat(hidden.path("calibration").path("source").asString()).isEqualTo("監視(address hidden)");
	}

	@Test
	void hideAddresses_sourceOutsideACalibration_isKept() {
		JsonNode value = json("{\"source\": \"https://example.com\"}");

		assertThat(SharePayloads.hideAddresses(value)).isEqualTo(value);
	}

	@Test
	void publicReport_withTraces_leavesThemOut() {
		JsonNode shared = SharePayloads.publicReport(json("{\"summary\": {}, \"traces\": [1, 2]}"));

		assertThat(shared).isEqualTo(json("{\"summary\": {}}"));
	}

	private static JsonNode json(String text) {
		return JSON.readTree(text);
	}

}
