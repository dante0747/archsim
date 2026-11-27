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
	void hideAddresses_measuredInputsProvenance_losesTheAddressAndKeepsTheQuery() {
		JsonNode hidden = SharePayloads.hideAddresses(json("""
				{"provenance": {"nodes/api/serviceTimeMs": {"kind": "measured",
				  "source": "Prometheus https://user:pass@prom.internal:9090", "query": "rate(requests[5m])"}}}"""));

		assertThat(hidden).isEqualTo(json("""
				{"provenance": {"nodes/api/serviceTimeMs": {"kind": "measured",
				  "source": "Prometheus (address hidden)", "query": "rate(requests[5m])"}}}"""));
	}

	@Test
	void hideAddresses_reportsUserInputs_loseTheAddress() {
		JsonNode hidden = SharePayloads.hideAddresses(json("""
				{"assumptions": {"userInputs": [{"field": "serviceTimeMs",
				  "provenance": {"kind": "measured", "source": "Prometheus http://10.0.0.7:9090"}}]}}"""));

		assertThat(hidden.at("/assumptions/userInputs/0/provenance/source").asString())
			.isEqualTo("Prometheus (address hidden)");
	}

	@Test
	void hideAddresses_valuesWithoutAnAddress_areKept() {
		JsonNode value = json("""
				{"provenance": {"x": {"source": "observed rate over 2 h", "fitError": 0.1, "note": null}}}""");

		assertThat(SharePayloads.hideAddresses(value)).isEqualTo(value);
	}

	@Test
	void hideAddresses_aDocumentedInputsLink_isKept() {
		JsonNode value = json(
				"""
						{"assumptions": {"userInputs": [{"field": "gcPauses",
						  "provenance": {"kind": "documented", "source": "Go 1.8 release notes https://go.dev/doc/go1.8"}}]}}""");

		assertThat(SharePayloads.hideAddresses(value)).isEqualTo(value);
	}

	@Test
	void hideAddresses_aFormulasDocumentationLink_isKept() {
		JsonNode value = json("""
				{"parts": [{"explain": [{"name": "Little's law",
				  "source": "https://en.wikipedia.org/wiki/Little%27s_law"}]}]}""");

		assertThat(SharePayloads.hideAddresses(value)).isEqualTo(value);
	}

	@Test
	void hideAddresses_anAddressInTheDesignsOwnWords_isKept() {
		JsonNode value = json("""
				{"metadata": {"description": "Runbook: https://wiki.example.com/shop"}}""");

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
