package io.archsim.benchmarks.report;

import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

class RepeatabilityTests {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	@Test
	void comparable_runsUnderOtherConditions_areLeftOut() {
		List<JsonNode> runs = List.of(run("protocol", "v4"), run("os", "w0"), run("engineVersion", "0.14.0"),
				run("machine", "b"), run(), run());

		List<JsonNode> alike = Repeatability.comparable(runs);

		assertThat(alike).containsExactly(run(), run());
	}

	// an unrecorded power mode is not the same as a recorded one
	@Test
	void comparable_runsUnderAnotherPowerMode_areLeftOut() {
		List<JsonNode> runs = List.of(run(), run("powerMode", "Balanced"), run("powerMode", "Best performance"),
				run("powerMode", "Best performance"));

		List<JsonNode> alike = Repeatability.comparable(runs);

		assertThat(alike).containsExactly(run("powerMode", "Best performance"), run("powerMode", "Best performance"));
	}

	@Test
	void comparable_noRuns_isNone() {
		assertThat(Repeatability.comparable(List.of())).isEmpty();
	}

	@Test
	void of_threeRunsWithRanges_countsTheRunsInsideThem() {
		List<JsonNode> runs = List.of(ranged(-0.3, true), ranged(-0.1, false), ranged(-0.2, true));

		JsonNode repeatability = Repeatability.of(runs).orElseThrow();

		assertThat(repeatability.get("runs").intValue()).isEqualTo(3);
		assertThat(repeatability.get("medianError").doubleValue()).isEqualTo(-0.2);
		assertThat(repeatability.get("inRange").intValue()).isEqualTo(2);
		assertThat(repeatability.get("withRange").intValue()).isEqualTo(3);
	}

	@Test
	void of_threeRunsWithRanges_saysSoInTheTablesWords() {
		List<JsonNode> runs = List.of(ranged(-0.3, true), ranged(-0.1, false), ranged(-0.2, true));

		JsonNode repeatability = Repeatability.of(runs).orElseThrow();

		assertThat(repeatability.get("text")).isEqualTo(JsonText.read("""
				{"medianError": "-20%", "errors": "-30% to -10%", "meanAbsError": "20%", "coverage": "2 of 3"}"""));
	}

	@Test
	void of_threeRunsWithHalfRanges_expectsHalfOfThemInside() {
		List<JsonNode> runs = List.of(ranged(-0.3, true), ranged(-0.1, false), ranged(-0.2, true));

		JsonNode repeatability = Repeatability.of(runs).orElseThrow();

		assertThat(repeatability.get("expectedInRange").doubleValue()).isEqualTo(1.5);
	}

	@Test
	void of_threeRunsWithHalfRangesTwoInside_isAsClaimed() {
		List<JsonNode> runs = List.of(ranged(-0.3, true), ranged(-0.1, false), ranged(-0.2, true));

		JsonNode repeatability = Repeatability.of(runs).orElseThrow();

		assertThat(repeatability.get("coverageVerdict").stringValue()).isEqualTo("as claimed");
	}

	// Python's sum() of nothing is the integer 0, and round() keeps it one
	@Test
	void of_runsWithoutRanges_expectTheIntegerZero() {
		List<JsonNode> runs = List.of(ranged(-0.3, null), ranged(-0.1, null), ranged(-0.2, null));

		JsonNode repeatability = Repeatability.of(runs).orElseThrow();

		assertThat(repeatability.get("expectedInRange")).isEqualTo(NODES.numberNode(0));
	}

	@Test
	void of_oneRunWithoutARange_hasOneErrorAndNoCoverage() {
		JsonNode repeatability = Repeatability.of(List.of(ranged(-0.08, null))).orElseThrow();

		assertThat(repeatability.get("text")).isEqualTo(JsonText.read("""
				{"medianError": "-8%", "errors": "-8%", "meanAbsError": "8%", "coverage": "-"}"""));
	}

	// misses of 30%, 10% and 20%, on either side: the median says -20%
	@Test
	void of_runsMissingOnEitherSide_takesTheMeanOfTheirErrorsSizes() {
		List<JsonNode> runs = List.of(ranged(-0.3, true), ranged(0.1, false), ranged(-0.2, true));

		JsonNode repeatability = Repeatability.of(runs).orElseThrow();

		assertThat(repeatability.at("/text/meanAbsError").stringValue()).isEqualTo("20%");
	}

	@Test
	void meanAbs_noErrors_isNone() {
		assertThat(Repeatability.meanAbs(List.of()).isNull()).isTrue();
	}

	@Test
	void of_noRuns_isEmpty() {
		assertThat(Repeatability.of(List.of())).isEmpty();
	}

	@Test
	void of_disturbedRuns_areLeftOut() {
		JsonNode repeatability = Repeatability.of(History.withTwoDisturbed()).orElseThrow();

		assertThat(List.of(repeatability.get("runs"), repeatability.get("disturbed"), repeatability.get("withRange")))
			.extracting(JsonNode::intValue)
			.containsExactly(2, 2, 2);
		assertThat(repeatability.at("/text/errors").stringValue()).isEqualTo("+10% to +20%");
	}

	// with every comparable run disturbed there is nothing to judge, and no error range
	@Test
	void of_everyRunDisturbed_judgesNone() {
		JsonNode repeatability = Repeatability.of(History.withTwoDisturbed().subList(0, 2)).orElseThrow();

		assertThat(repeatability.get("runs").intValue()).isZero();
		assertThat(repeatability.get("medianError").isNull()).isTrue();
	}

	// 12 runs at 50%: 2 or fewer inside happen under 5% of the time, as do 10
	// or more; at 80%, 6 or fewer of 12 are too few, and no count is too many
	@ParameterizedTest(name = "{0} of {1} inside, {2} expected: {3}")
	@CsvSource({ "1, 12, 6.0, too narrow", "2, 12, 6.0, too narrow", "3, 12, 6.0, as claimed", "10, 12, 6.0, too wide",
			"6, 12, 9.6, too narrow", "7, 12, 9.6, as claimed", "12, 12, 9.6, as claimed" })
	void coverageVerdict_aCount_isJudgedByTheBinomialTails(int hits, int runs, double expected, String verdict) {
		assertThat(Repeatability.coverageVerdict(hits, runs, expected)).contains(verdict);
	}

	@Test
	void coverageVerdict_noRuns_isNone() {
		assertThat(Repeatability.coverageVerdict(0, 0, 0.0)).isEmpty();
	}

	@Test
	void coverageSummary_everyExperimentsRanges_areJudgedTogether() {
		List<JsonNode> repeatabilities = List.of(summary(3, 2, 2.4, 0.8), summary(3, 3, 2.4, 0.8), NODES.nullNode(),
				summary(0, 0, 0.0, null));

		JsonNode summary = Repeatability.coverageSummary(repeatabilities);

		assertThat(summary).isEqualTo(JsonText.read("""
				{"withRange": 6, "inRange": 5, "expectedInRange": 4.8, "nominalCoverage": 0.8,
				 "verdict": "as claimed"}"""));
	}

	@Test
	void coverageSummary_rangesOfDifferentCoverages_haveNoNominalCoverage() {
		List<JsonNode> repeatabilities = List.of(summary(3, 2, 2.4, 0.8), summary(3, 0, 1.5, 0.5));

		JsonNode summary = Repeatability.coverageSummary(repeatabilities);

		assertThat(summary.get("nominalCoverage").isNull()).isTrue();
	}

	private static ObjectNode run(String... overrides) {
		ObjectNode run = NODES.objectNode()
			.put("protocol", "v5")
			.put("machine", "a")
			.put("os", "w1")
			.put("engineVersion", "0.15.0");
		for (int i = 0; i < overrides.length; i += 2) {
			run.put(overrides[i], overrides[i + 1]);
		}
		return run;
	}

	private static ObjectNode ranged(double error, @Nullable Boolean inside) {
		ObjectNode run = NODES.objectNode().put("protocol", "v5").put("machine", "a").put("kneeError", error);
		if (inside == null) {
			run.putNull("kneeRange");
		}
		else {
			run.putObject("kneeRange").put("nominalCoverage", 0.5).put("containsMeasured", inside);
		}
		return run;
	}

	private static ObjectNode summary(int withRange, int inRange, double expected, @Nullable Double nominal) {
		ObjectNode summary = NODES.objectNode().put("withRange", withRange).put("inRange", inRange);
		summary.put("expectedInRange", expected);
		return (nominal != null) ? summary.put("nominalCoverage", nominal) : summary.putNull("nominalCoverage");
	}

	/**
	 * Four runs of one experiment, the first two disturbed, as the history records them.
	 */
	static final class History {

		private History() {
		}

		static List<JsonNode> withTwoDisturbed() {
			return Arrays.asList(entry(2.9, 1089.0, 2112), entry(2.5, 1449.0, 2550), entry(0.2, 4160.0),
					entry(0.1, 4400.0));
		}

		private static JsonNode entry(double error, double knee, int... disturbed) {
			ObjectNode run = NODES.objectNode().put("dir", "latest").put("protocol", "v5");
			run.putNull("calibratedAt");
			run.put("machine", "a").put("os", "x").put("kneeError", error);
			run.putObject("kneeRange").put("nominalCoverage", 0.5).put("containsMeasured", false);
			run.putObject("measuredKnee").put("kneeRps", knee).put("bound", "interpolated");
			run.putObject("checks").set("disturbed", Runs.rates(disturbed));
			return run;
		}

	}

}
