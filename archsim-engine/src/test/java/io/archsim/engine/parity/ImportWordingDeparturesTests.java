package io.archsim.engine.parity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The frozen engine's words for what the autoscaler reads, read as the Java words them;
 * every other text stays as recorded.
 */
class ImportWordingDeparturesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void asJavaWordsIt_anIgnoredMetric_saysWhatTheAutoscalerReads() {
		JsonNode worded = ImportWordingDepartures.asJavaWordsIt(recorded());

		assertThat(worded.at("/report/ignored/0").stringValue()).isEqualTo("api: memory metric "
				+ "(ArchSim scales on worker utilization or, with a CPU size, CPU utilization if higher)");
	}

	@Test
	void asJavaWordsIt_aGuessedTarget_noLongerSaysCpu() {
		JsonNode worded = ImportWordingDepartures.asJavaWordsIt(recorded());

		assertThat(worded.at("/report/guessed/0").stringValue())
			.isEqualTo("api has no CPU target: api scales at ArchSim's default target");
	}

	@Test
	void asJavaWordsIt_otherTexts_stayAsRecorded() {
		JsonNode worded = ImportWordingDepartures.asJavaWordsIt(recorded());

		assertThat(worded.at("/report/guessed/1").stringValue()).isEqualTo("db: a guess");
		assertThat(worded.at("/report/ignored/1").stringValue()).isEqualTo("a volume");
	}

	@Test
	void asJavaWordsIt_thePhraseBeforeTheEndOfAText_staysAsRecorded() {
		JsonNode worded = ImportWordingDepartures.asJavaWordsIt(recorded());

		assertThat(worded.at("/report/guessed/2").stringValue())
			.isEqualTo("db scales on CPU at ArchSim's default target, for now");
		assertThat(worded.at("/report/ignored/2").stringValue())
			.isEqualTo("a metric (ArchSim scales on CPU utilization), for now");
	}

	@Test
	void asJavaWordsIt_aRecording_leavesItAsItWas() {
		JsonNode recorded = recorded();

		ImportWordingDepartures.asJavaWordsIt(recorded);

		assertThat(recorded).isEqualTo(recorded());
	}

	private static JsonNode recorded() {
		return JSON.readTree("""
				{"report": {
				  "guessed": [
				    "api has no CPU target: api scales on CPU at ArchSim's default target",
				    "db: a guess",
				    "db scales on CPU at ArchSim's default target, for now"],
				  "ignored": [
				    "api: memory metric (ArchSim scales on CPU utilization)",
				    "a volume",
				    "a metric (ArchSim scales on CPU utilization), for now"]}}""");
	}

}
