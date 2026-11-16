package io.archsim.engine.parity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The frozen engine's words read as the Java words them: one request is a request, where
 * Python wrote "1 requests"; every other count, and every other word, stays as recorded.
 */
class WordingDeparturesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void asJavaWordsIt_oneFailedRequest_isSingular() {
		String text = WordingDepartures.asJavaWordsIt(
				"[warning] Main cause of failed requests: 1 requests failed at API: third-party API returned an error");

		assertThat(text).isEqualTo(
				"[warning] Main cause of failed requests: 1 request failed at API: third-party API returned an error");
	}

	@Test
	void asJavaWordsIt_oneClientRequestFailingBecauseOfAPart_isSingular() {
		String text = WordingDepartures.asJavaWordsIt(
				"1 of 500 calls (0.2%) failed with provider errors; 1 client requests failed because of them.");

		assertThat(text)
			.isEqualTo("1 of 500 calls (0.2%) failed with provider errors; 1 client request failed because of them.");
	}

	@Test
	void asJavaWordsIt_countsEndingInOneOrAboveOne_stayAsRecorded() {
		String recorded = "21 requests failed at API; 1,001 requests hit the client timeout; 0.1 requests failed";

		assertThat(WordingDepartures.asJavaWordsIt(recorded)).isEqualTo(recorded);
	}

	@Test
	void findingsAsJavaWordsThem_aReport_changesOnlyItsFindingsDetails() {
		JsonNode report = recordedReport();

		JsonNode worded = WordingDepartures.findingsAsJavaWordsThem(report);

		assertThat(worded.at("/findings/0/detail").stringValue()).isEqualTo("1 request failed at API: queue was full");
		assertThat(worded.at("/findings/0/title").stringValue()).isEqualTo("1 requests failed at API");
		assertThat(worded.at("/summary/note").stringValue()).isEqualTo("1 requests failed at API");
	}

	@Test
	void findingsAsJavaWordsThem_aReport_leavesTheRecordedReportAsItWas() {
		JsonNode report = recordedReport();

		WordingDepartures.findingsAsJavaWordsThem(report);

		assertThat(report.at("/findings/0/detail").stringValue()).isEqualTo("1 requests failed at API: queue was full");
	}

	private static JsonNode recordedReport() {
		return JSON.readTree("""
				{"summary": {"note": "1 requests failed at API"},
				 "findings": [{"title": "1 requests failed at API",
				               "detail": "1 requests failed at API: queue was full"}]}""");
	}

}
