package io.archsim.benchmarks.range;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.resimulation.CheckStamp;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.KeptRuns;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonJson;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code benchmarks/knee-range.json}, fitted on 20 Nov 2026 by {@code range-fit} with
 * engine 1.6.0 from {@code 1.6.0-law}'s development checks (ADR-0094), refitted here from
 * what it records itself: the calibrations' scales and each checked run's knees and
 * sensitivity, with the range each run's comparison records. The variation, the model's
 * error, the counts, every run's ranges and the whole file but for what names it come out
 * the same. (Its knees and sensitivities are simulated and its inputs derived again from
 * the calibrations, which takes minutes; the test refits from them.)
 */
class CommittedRangeFitTests {

	private static final JsonNode FIT = ResultFiles.read(RepositoryLayout.current().kneeRange()).orElseThrow();

	private static final List<JsonNode> CALIBRATIONS = FIT.get("calibrations").valueStream().toList();

	private static final List<KeptRun> KEPT = KeptRuns.of(RepositoryLayout.current().results());

	/**
	 * Each checked run as the fit read it, with the range its comparison records. The run
	 * is found by its calibration, which stays with it: the fit's latest runs have moved
	 * into {@code run-N/} since.
	 */
	private static final List<CountedRun> RUNS = FIT.get("checked")
		.valueStream()
		.map((run) -> new CountedRun(run.get("experiment").stringValue(), run.get("source").stringValue(),
				run.get("calibratedAt"), run.get("measuredKnee"), run.get("knee"), (ObjectNode) run.get("sensitivity"),
				StrictMath.log(run.at("/measuredKnee/kneeRps").doubleValue() / run.get("knee").doubleValue()),
				comparisonOf(run.get("calibratedAt")).get("kneeRange")))
		.toList();

	/** What names a fit, and how it was made: the Java harness's own. */
	private static final List<String> NAMES = List.of("at", "engineVersion", "inputsAndKneesFrom", "commit",
			"provenance", "how");

	@Test
	void document_theCommittedCalibrationsAndRuns_isTheCommittedFileButForWhatNamesIt() {
		CheckStamp stamp = new CheckStamp("1.6.0", JsonNodeFactory.instance.objectNode(),
				Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC));

		ObjectNode document = RangeFit.document(new RangeFit.Fitted(CALIBRATIONS, RUNS), stamp, "1.6.0-law");

		ObjectNode committed = ((ObjectNode) FIT).deepCopy();
		NAMES.forEach((name) -> {
			document.remove(name);
			committed.remove(name);
		});
		assertThat(PythonJson.writeIndented(document, 2)).isEqualTo(PythonJson.writeIndented(committed, 2));
	}

	@Test
	void committedFit_nominalCoverage_isTheRangesCoverage() {
		assertThat(FIT.get("nominalCoverage").doubleValue()).isEqualTo(KneeRanges.COVERAGE);
	}

	// the counted runs from protocol v5 on of the nine experiments engine 1.6.0 is tuned
	// on
	@Test
	void committedFit_runs_areFortyNine() {
		assertThat(FIT.get("runs").intValue()).isEqualTo(49);
	}

	@Test
	void committedFit_experiments_areNine() {
		assertThat(FIT.get("experiments").intValue()).isEqualTo(9);
	}

	@Test
	void committedFit_calibrations_areTheNineMeasuredExperiments() {
		assertThat(CALIBRATIONS.stream().map((row) -> row.get("experiment").stringValue()).distinct())
			.containsExactlyInAnyOrder("pg-mixed", "pg-hot-row", "cache-aside", "kafka", "java-mixed", "go-mixed",
					"python-mixed", "chain", "cache-ttl");
	}

	@Test
	void committedFit_eachInput_isAVariedOne() {
		assertThat(FIT.get("inputs").propertyNames()).isSubsetOf(RangeInputs.VARIED);
	}

	@Test
	void committedFit_eachInputsVariation_isNotNegative() {
		assertThat(FIT.get("inputs").valueStream().map((input) -> input.get("sigma").doubleValue()))
			.allMatch((sigma) -> sigma >= 0);
	}

	@Test
	void committedFit_modelsError_isBetweenZeroAndOne() {
		assertThat(FIT.at("/model/sigma").doubleValue()).isStrictlyBetween(0.0, 1.0);
	}

	@Test
	void committedFit_correlation_isBetweenZeroAndOne() {
		assertThat(FIT.get("correlation").doubleValue()).isBetween(0.0, 1.0);
	}

	// the ranges the runs were predicted with: protocol v5's replicate sets' knees held 1
	// of 12, W28's range 29 of 37
	@Test
	void committedFit_theRangesTheRunsWerePredictedWith_heldOneOfTwelveAndTwentyNineOfThirtySeven() {
		assertThat(FIT.at("/check/before")).isEqualTo(JsonText.read("""
				[{"nominalCoverage": 0.5, "inRange": 1, "withRange": 12},
				 {"nominalCoverage": 0.8, "inRange": 29, "withRange": 37}]"""));
	}

	// leaving out each run's experiment: 41 of 49 (84%) inside the 80% range, where the
	// model error fitted on the four tuned-on experiments held 32
	@Test
	void committedFit_leavingOutEachExperiment_holdsFortyOneOfFortyNine() {
		assertThat(FIT.at("/check/counts/leaveExperimentOut/0.8")).isEqualTo(JsonText.read("[41, 49]"));
	}

	@Test
	void committedFit_eachFoldsCount_isItsRunsInsideTheirRange() {
		assertThat(RangeCheck.FOLDS.keySet())
			.allSatisfy((fold) -> assertThat(FIT.at("/check/counts/" + fold + "/0.8/0").intValue())
				.isEqualTo((int) FIT.get("checked")
					.valueStream()
					.filter((run) -> run.at("/ranges/" + fold + "/holds").booleanValue())
					.count()));
	}

	@Test
	void foldFit_theCommittedCalibrationsAndRuns_isTheCommittedFit() {
		ObjectNode fit = RangeCheck.foldFit(CALIBRATIONS, RUNS);

		ObjectNode committed = JsonNodeFactory.instance.objectNode();
		List.of("inputs", "fallbackSigma", "correlation", "model").forEach((key) -> committed.set(key, FIT.get(key)));
		assertThat(PythonJson.writeIndented(fit, 2)).isEqualTo(PythonJson.writeIndented(committed, 2));
	}

	@Test
	void check_theCommittedCalibrationsAndRuns_isTheCommittedCounts() {
		RangeCheck.Result check = RangeCheck.check(CALIBRATIONS, RUNS);

		assertThat(PythonJson.writeIndented(check.counts(), 2))
			.isEqualTo(PythonJson.writeIndented(FIT.at("/check/counts"), 2));
	}

	@Test
	void check_theCommittedCalibrationsAndRuns_givesEachRunItsCommittedRanges() {
		RangeCheck.Result check = RangeCheck.check(CALIBRATIONS, RUNS);

		assertThat(check.detail().stream().map((ranges) -> PythonJson.writeIndented(ranges, 2)).toList()).isEqualTo(
				FIT.get("checked").valueStream().map((run) -> PythonJson.writeIndented(run.get("ranges"), 2)).toList());
	}

	@Test
	void calibrationSigma_eachCommittedRun_isItsCommittedSpread() {
		List<Double> spreads = RUNS.stream()
			.map((run) -> PythonRound.round(KneeRanges.calibrationSigma(run.sensitivity(), FIT), 4))
			.toList();

		assertThat(spreads).isEqualTo(
				FIT.get("checked").valueStream().map((run) -> run.get("calibrationSigma").doubleValue()).toList());
	}

	@Test
	void error_eachCommittedRun_isItsCommittedLogError() {
		List<Double> errors = RUNS.stream().map((run) -> PythonRound.round(run.error(), 4)).toList();

		assertThat(errors)
			.isEqualTo(FIT.get("checked").valueStream().map((run) -> run.get("error").doubleValue()).toList());
	}

	// each checked run's in-sample range is the committed fit applied to its sensitivity
	@Test
	void of_eachCommittedRun_isItsInSampleRange() {
		List<String> ranges = IntStream.range(0, RUNS.size()).mapToObj((index) -> {
			ObjectNode range = KneeRanges
				.of(RUNS.get(index).knee(), RUNS.get(index).sensitivity(), FIT, KneeRanges.COVERAGE)
				.orElseThrow();
			return range.get("lowRps") + "-" + range.get("highRps");
		}).toList();

		assertThat(ranges).isEqualTo(FIT.get("checked")
			.valueStream()
			.map((run) -> run.at("/ranges/inSample/lowRps") + "-" + run.at("/ranges/inSample/highRps"))
			.toList());
	}

	private static JsonNode comparisonOf(JsonNode calibratedAt) {
		KeptRun run = KEPT.stream()
			.filter((kept) -> ResultFiles.read(kept.file(KeptRun.CALIBRATION))
				.map((calibration) -> calibration.path("at").equals(calibratedAt))
				.orElse(false))
			.findFirst()
			.orElseThrow();
		return ResultFiles.read(run.file(KeptRun.COMPARISON)).orElseThrow();
	}

}
