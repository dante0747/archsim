package io.archsim.benchmarks.campaign;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.HarnessStopException;
import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * An experiment's run on pg-mixed's latest run played back ({@link PlayedSteps}): the
 * ladder's top meeting the objective, so that the ladder climbs above its prediction, or
 * a knee between two of its steps or below them all, so that the run closes in on it;
 * each step it adds predicted, saved and sealed before it is measured.
 */
class ExperimentRunTests {

	private static final Experiment PG_MIXED = Experiments.standard().get("pg-mixed");

	private final List<String> calls = new ArrayList<>();

	@Test
	void run_aSealedExperiment_isRefusedBeforeItIsCalibrated(@TempDir Path results) {
		PlayedSteps steps = new PlayedSteps(results, PlayedSteps.Script.TOP_MEETS, this.calls);

		Throwable thrown = catchThrowable(() -> new ExperimentRun(steps, results,
				(experiment) -> Optional.of("pg-mixed stays sealed"), new Logs().log())
			.run(PG_MIXED));

		assertThat(thrown).isInstanceOf(HarnessStopException.class).hasMessage("pg-mixed stays sealed");
		assertThat(this.calls).isEmpty();
	}

	@Test
	void run_aLadderMeetingTheObjectiveAtItsTop_measuresAStepAbove(@TempDir Path results) {
		PlayedSteps steps = run(results, PlayedSteps.Script.TOP_MEETS);

		assertThat(this.calls).last().isEqualTo("measure " + above(steps));
	}

	// 4225 x 1.3 = 5492.5, rounded half to even as Python's round() rounds
	@Test
	void run_aStepAbove_isRoundedHalfToEven(@TempDir Path results) {
		run(results, new PlayedSteps.Script(false, 4225, ""));

		assertThat(this.calls).last().isEqualTo("measure 5492");
	}

	@Test
	void run_aLadderMeetingTheObjectiveAtEveryStep_climbsFourStepsAtMost(@TempDir Path results) {
		PlayedSteps steps = run(results, new PlayedSteps.Script(true, 0, ""));

		assertThat(this.calls.subList(this.calls.indexOf("measure " + steps.top()) + 1, this.calls.size()))
			.hasSize(ExperimentRun.MAX_EXTENSIONS);
	}

	@Test
	void run_aStepAboveTheLadder_isPredictedAsAnExtension(@TempDir Path results) {
		run(results, PlayedSteps.Script.TOP_MEETS);

		JsonNode steps = read(results, "predictions.json").get("steps");
		assertThat(steps.get(steps.size() - 1).get("extension").booleanValue()).isTrue();
	}

	// the predictions the ladder began with, as they were before its first step
	@Test
	void run_theLadder_isSealedBeforeItsFirstStep(@TempDir Path results) {
		PlayedSteps steps = run(results, PlayedSteps.Script.TOP_MEETS);

		assertThat(read(results, "measured.json").get("predictionsSha256").stringValue())
			.isEqualTo(steps.digests().getFirst());
	}

	// run.py saved the extension into the sealed file after its one seal
	@Test
	void run_anExtension_isSealedWithThePredictionsAsTheyWereBeforeItsStep(@TempDir Path results) {
		PlayedSteps steps = run(results, PlayedSteps.Script.TOP_MEETS);

		assertThat(read(results, "measured.json").get("extensionSeals")).isEqualTo(JsonText.read("[{\"offeredRps\": "
				+ above(steps) + ", \"predictionsSha256\": \"" + steps.digests().getLast() + "\"}]"));
	}

	// a run cut short by its step still leaves the step's seal behind
	@Test
	void run_anExtension_isSealedOnDiskBeforeItsStepIsMeasured(@TempDir Path results) {
		PlayedSteps steps = run(results, PlayedSteps.Script.TOP_MEETS);

		assertThat(steps.sealsHeld().getLast()).isEqualTo(read(results, "measured.json").get("extensionSeals"));
	}

	@Test
	void run_anExtension_logsItsSeal(@TempDir Path results) {
		Logs logs = new Logs();
		PlayedSteps steps = new PlayedSteps(results, PlayedSteps.Script.TOP_MEETS, this.calls);

		new ExperimentRun(steps, results, (experiment) -> Optional.empty(), logs.log()).run(PG_MIXED);

		assertThat(logs.text().lines()).contains("[12:34:56] pg-mixed: predictions saved with " + above(steps)
				+ " req/s, sha256 " + steps.digests().getLast());
	}

	// a third above the lowest step: between it and the second, which are 100% apart
	@Test
	void run_aKneeBetweenTwoSteps_measuresBetweenThemUntilTheyAreWithinThreePercent(@TempDir Path results) {
		run(results, PlayedSteps.Script.kneeAt(PlayedSteps.lowest() * 4 / 3));

		JsonNode between = read(results, "comparison.json").at("/measuredKnee/between");
		assertThat(between.get(0).doubleValue() / between.get(1).doubleValue())
			.isGreaterThanOrEqualTo(1 - KneeBracket.RESOLUTION);
	}

	// a tenth above the top: the ladder climbs 30% above it, then closes in between the
	// two
	@Test
	void run_aKneeAboveTheLadder_closesInBetweenItsTopAndTheStepAbove(@TempDir Path results) {
		run(results, PlayedSteps.Script.kneeAt(PlayedSteps.highest() * 11 / 10));

		JsonNode between = read(results, "comparison.json").at("/measuredKnee/between");
		assertThat(between.get(0).longValue()).isGreaterThan(PlayedSteps.highest());
		assertThat(between.get(0).doubleValue() / between.get(1).doubleValue())
			.isGreaterThanOrEqualTo(1 - KneeBracket.RESOLUTION);
	}

	@Test
	void run_aStepBetweenTwoOfTheLadder_isPredictedAsARefinement(@TempDir Path results) {
		run(results, PlayedSteps.Script.kneeAt(PlayedSteps.lowest() * 4 / 3));

		JsonNode steps = read(results, "predictions.json").get("steps");
		assertThat(steps.get(steps.size() - 1).get("refinement").booleanValue()).isTrue();
	}

	// each step between two of the ladder's is sealed before it is measured, as one above
	// it is
	@Test
	void run_aRefinement_isSealedWithThePredictionsAsTheyWereBeforeItsStep(@TempDir Path results) {
		PlayedSteps steps = run(results, PlayedSteps.Script.kneeAt(PlayedSteps.lowest() * 4 / 3));

		JsonNode seals = read(results, "measured.json").get("extensionSeals");
		assertThat(seals.get(seals.size() - 1).get("predictionsSha256").stringValue())
			.isEqualTo(steps.digests().getLast());
	}

	// a stack far slower than predicted: no step of the ladder meets the objective
	@Test
	void run_aLadderMissingTheObjectiveFromItsLowestStep_measuresHalfOfIt(@TempDir Path results) {
		PlayedSteps steps = run(results, PlayedSteps.Script.kneeAt(1));

		assertThat(this.calls.get(this.calls.indexOf("measure " + steps.top()) + 1))
			.isEqualTo("measure " + (long) Math.rint(PlayedSteps.lowest() / 2.0));
	}

	// a knee at a twentieth of the lowest step: five halvings reach below it, and closing
	// in between the last two would take five more
	@Test
	void run_aKneeTheSearchCannotReachInEightSteps_addsEightSteps(@TempDir Path results) {
		PlayedSteps steps = run(results, PlayedSteps.Script.kneeAt(PlayedSteps.lowest() / 20));

		assertThat(this.calls.subList(this.calls.indexOf("measure " + steps.top()) + 1, this.calls.size()))
			.hasSize(ExperimentRun.MAX_REFINEMENTS);
	}

	@Test
	void run_aSearchItsCapEnded_saysSo(@TempDir Path results) {
		Logs logs = new Logs();
		PlayedSteps steps = new PlayedSteps(results, PlayedSteps.Script.kneeAt(PlayedSteps.lowest() / 20), this.calls);

		new ExperimentRun(steps, results, (experiment) -> Optional.empty(), logs.log()).run(PG_MIXED);

		assertThat(logs.text().lines())
			.contains("[12:34:56] pg-mixed: stopped closing in on the knee after 8 steps; it is not yet known to 3%");
	}

	// what a step was measured for travels with measured.json, whatever it is compared
	// with
	@Test
	void run_aStepBetweenTwoOfTheLadder_isMarkedInTheMeasurementsToo(@TempDir Path results) {
		run(results, PlayedSteps.Script.kneeAt(PlayedSteps.lowest() * 4 / 3));

		JsonNode steps = read(results, "measured.json").get("steps");
		assertThat(steps.get(steps.size() - 1).get("refinement").booleanValue()).isTrue();
	}

	@Test
	void run_anExperiment_writesItsComparison(@TempDir Path results) {
		run(results, PlayedSteps.Script.TOP_MEETS);

		assertThat(read(results, "comparison.json").get("experiment").stringValue()).isEqualTo("pg-mixed");
	}

	@Test
	void run_anExperiment_keepsPostgresqlsCheckpointsAndVacuumsAlone(@TempDir Path results) throws IOException {
		run(results, PlayedSteps.Script.TOP_MEETS);

		assertThat(Files.readString(results.resolve("pg-mixed/postgres-events.log")))
			.isEqualTo("LOG:  checkpoint starting: time\nLOG:  automatic vacuum of table \"bench.public.items\"\n");
	}

	// these predictions carry CPU and memory: a re-simulation left by older results is
	// stale
	@Test
	void run_anExperiment_deletesAStaleResimulation(@TempDir Path results) throws IOException {
		Files.createDirectories(results.resolve("pg-mixed"));
		Files.writeString(results.resolve("pg-mixed/resources-predicted.json"), "{}");

		run(results, PlayedSteps.Script.TOP_MEETS);

		assertThat(results.resolve("pg-mixed/resources-predicted.json")).doesNotExist();
	}

	private PlayedSteps run(Path results, PlayedSteps.Script script) {
		PlayedSteps steps = new PlayedSteps(results, script, this.calls);
		new ExperimentRun(steps, results, (experiment) -> Optional.empty(), new Logs().log()).run(PG_MIXED);
		return steps;
	}

	// the step above the ladder's top, 30% higher, rounded half to even
	private static long above(PlayedSteps steps) {
		return (long) Math.rint(steps.top() * 1.3);
	}

	private static JsonNode read(Path results, String name) {
		return ResultFiles.read(results.resolve("pg-mixed").resolve(name)).orElseThrow();
	}

}
