package io.archsim.benchmarks.report;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.values.PythonValues;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The words the page puts beside its numbers: percentages, protocols, the run history's
 * "Counted" cell, a range's label and the harness's known flaws.
 */
class PageWordsTests {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	@ParameterizedTest(name = "{0}: {1}")
	@CsvSource({ "-0.0023, 0%", "0.004, 0%", "-0.006, -1%", "0.159, +16%" })
	void percent_aSignedShare_isNeverMinusZero(double share, String text) {
		assertThat(Figures.percent(NODES.numberNode(share))).isEqualTo(text);
	}

	// unsigned figures are magnitudes
	@Test
	void magnitude_aSmallNegativeShare_keepsItsSign() {
		assertThat(Figures.magnitude(NODES.numberNode(-0.0023))).isEqualTo("-0%");
	}

	@ParameterizedTest(name = "{0}: {1}")
	@CsvSource(delimiter = '|', textBlock = """
			two-pass slope, ... settled first (v4) | v4
			two-pass slope (v2)                    | v2
			""")
	void protocolOf_aCalibrationsMethod_namesItsProtocol(String method, String protocol) {
		JsonNode calibration = NODES.objectNode().put("method", method);

		assertThat(RunHistory.protocolOf(calibration)).isEqualTo(protocol);
	}

	// run 1 predates the method field
	@Test
	void protocolOf_noMethod_isTheFirstProtocol() {
		assertThat(RunHistory.protocolOf(NODES.objectNode())).isEqualTo("v1");
	}

	@Test
	void protocolOf_noCalibration_isTheFirstProtocol() {
		assertThat(RunHistory.protocolOf(PythonValues.none())).isEqualTo("v1");
	}

	@Test
	void countedText_aDisturbedRun_namesTheStepsItMissed() {
		JsonNode run = RepeatabilityTests.History.withTwoDisturbed().getFirst();

		assertThat(SummaryBlocks.countedText(run)).isEqualTo("no: missed at 2,112 req/s, met higher");
	}

	@Test
	void countedText_aRunNotDisturbed_isCounted() {
		JsonNode run = RepeatabilityTests.History.withTwoDisturbed().get(2);

		assertThat(SummaryBlocks.countedText(run)).isEqualTo("yes");
	}

	@Test
	void rangeLabel_aRangeFromReplicateSets_saysSo() {
		JsonNode range = JsonText.read("{\"replicates\": 3, \"nominalCoverage\": 0.5}");

		assertThat(ExperimentBlock.rangeLabel(range)).isEqualTo("range, from the 3 replicate sets,");
	}

	@Test
	void rangeLabel_aFittedRange_isItsCoverage() {
		JsonNode range = JsonText.read("{\"method\": \"...\", \"nominalCoverage\": 0.8}");

		assertThat(ExperimentBlock.rangeLabel(range)).isEqualTo("80% range");
	}

	// Kafka's broker healthcheck, fixed on 31 Oct 2026
	@Test
	void harnessCaveat_aRunBeforeTheFix_hasIt() {
		Optional<String> caveat = PageTexts.standard().harnessCaveat("kafka", NODES.stringNode("2026-10-31T03:26:27Z"));

		assertThat(caveat).hasValueSatisfying((text) -> assertThat(text).contains("healthcheck"));
	}

	@Test
	void harnessCaveat_aRunAfterTheFix_hasNone() {
		assertThat(PageTexts.standard().harnessCaveat("kafka", NODES.stringNode("2026-11-01T22:00:00Z"))).isEmpty();
	}

	@Test
	void harnessCaveat_anotherExperiment_hasNone() {
		assertThat(PageTexts.standard().harnessCaveat("pg-mixed", NODES.stringNode("2026-10-31T03:26:27Z"))).isEmpty();
	}

	@Test
	void harnessCaveat_noCalibration_hasNone() {
		assertThat(PageTexts.standard().harnessCaveat("kafka", PythonValues.none())).isEmpty();
	}

	@Test
	void inputValue_aLoadCurve_isEachFactorAtItsRate() {
		JsonNode curve = JsonText.read("[{\"rps\": 50, \"factor\": 4.03}, {\"rps\": 2610.4, \"factor\": 1.0}]");

		assertThat(ExperimentBlock.inputValue("serviceTimeByLoad", curve))
			.isEqualTo("x4.03 at 50, x1.00 at 2,610 req/s");
	}

	@Test
	void inputValue_aNumberInMilliseconds_saysSo() {
		assertThat(ExperimentBlock.inputValue("clientLinkLatencyMs", NODES.numberNode(0.25))).isEqualTo("0.25 ms");
	}

}
