package io.archsim.benchmarks.report;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

class TiersTests {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	// a factor of two either way: a prediction too low fails as surely as one too high
	@ParameterizedTest(name = "{0} ms measured, {1} predicted: {2}")
	@CsvSource({ "10.0, 19.9, true", "10.0, 20.1, false", "10.0, 5.1, true", "10.0, 4.9, false", "100.0, 0.5, false" })
	void p95Within_aFactorOfTwoEitherWay_isWithin(double measured, double predicted, boolean within) {
		boolean judged = Tiers.p95Within(NODES.numberNode(measured), NODES.numberNode(predicted));

		assertThat(judged).isEqualTo(within);
	}

	// a factor of 3.6 at 4 ms is only 2.9 ms below
	@ParameterizedTest(name = "{0} ms measured, {1} predicted: {2}")
	@CsvSource({ "1.0, 3.9, true", "1.0, 4.1, false", "4.0, 1.1, true", "2.0, 0.0, true" })
	void p95Within_threeMillisecondsWhereAFactorIsTooStrict_isWithin(double measured, double predicted,
			boolean within) {
		boolean judged = Tiers.p95Within(NODES.numberNode(measured), NODES.numberNode(predicted));

		assertThat(judged).isEqualTo(within);
	}

	@Test
	void p95Within_withoutAMeasuredP95_isNotWithin() {
		assertThat(Tiers.p95Within(NODES.nullNode(), NODES.numberNode(1.0))).isFalse();
	}

	@Test
	void p95Within_withoutAPredictedP95_isNotWithin() {
		assertThat(Tiers.p95Within(NODES.numberNode(1.0), NODES.nullNode())).isFalse();
	}

	@ParameterizedTest(name = "{0} ms measured, {1} predicted: x{2}")
	@CsvSource({ "10.0, 20.0, 2.0", "10.0, 5.0, 2.0", "10.0, 10.0, 1.0", "0.0, 0.0, 1.0", "10.0, 0.0, Infinity",
			"0.0, 10.0, Infinity" })
	void p95Factor_eitherWay_isTheSameFactor(double measured, double predicted, double factor) {
		double judged = Tiers.p95Factor(measured, predicted);

		assertThat(judged).isEqualTo(factor);
	}

	// three runs on machine a, the last the one compared, and one on machine b
	@Test
	void criteria_threeRunsAlikeOnTwoMachines_meetsAllThree() {
		JsonNode criteria = Tiers.criteria(passingComparison(), runsOnTwoMachines());

		assertThat(criteria.get("all").booleanValue()).isTrue();
	}

	@Test
	void criteria_threeRunsAlike_judgesTheMedianKneeErrorOverThem() {
		JsonNode criteria = Tiers.criteria(passingComparison(), runsOnTwoMachines());

		assertThat(criteria.get("knee")).isEqualTo(JsonText.read("""
				{"pass": true, "error": -0.2, "runs": 3, "disturbedRuns": 0, "thisRun": -0.2}"""));
	}

	@Test
	void criteria_threeRunsAlike_judgesTheP95InEachOfThem() {
		JsonNode criteria = Tiers.criteria(passingComparison(), runsOnTwoMachines());

		assertThat(criteria.get("p95")).isEqualTo(JsonText.read("""
				{"pass": true, "steps": 2, "misses": [], "runs": 3, "runsPassing": 3}"""));
	}

	@Test
	void criteria_runsOnTwoMachines_isReproduced() {
		JsonNode criteria = Tiers.criteria(passingComparison(), runsOnTwoMachines());

		assertThat(criteria.get("reproduced")).isEqualTo(JsonText.read("""
				{"pass": true, "protocol": "v5", "runs": 4, "machines": 2}"""));
	}

	// the median of -0.4, -0.3 and this run's -0.1
	@Test
	void criteria_errorsOverTheTolerance_failTheKneeByTheirMedian() {
		List<JsonNode> runs = List.of(Runs.passingRun(-0.4), Runs.passingRun(-0.3), Runs.latest("v5", "a"));

		JsonNode criteria = Tiers.criteria(oneStep(-0.1), runs);

		assertThat(criteria.at("/knee/error").doubleValue()).isEqualTo(-0.3);
		assertThat(criteria.at("/knee/pass").booleanValue()).isFalse();
	}

	@Test
	void criteria_p95WithinInTwoOfThreeRuns_meetsCriterionTwo() {
		List<JsonNode> runs = List.of(failingP95(Runs.passingRun(-0.2)), Runs.passingRun(-0.2), Runs.latest("v5", "a"));

		JsonNode criteria = Tiers.criteria(oneStep(-0.1), runs);

		assertThat(criteria.at("/p95/pass").booleanValue()).isTrue();
		assertThat(criteria.at("/p95/runsPassing").intValue()).isEqualTo(2);
	}

	@Test
	void criteria_p95WithinInOneOfThreeRuns_failsCriterionTwo() {
		List<JsonNode> runs = List.of(failingP95(Runs.passingRun(-0.2)), failingP95(Runs.passingRun(-0.2)),
				Runs.latest("v5", "a"));

		JsonNode criteria = Tiers.criteria(oneStep(-0.1), runs);

		assertThat(criteria.at("/p95/pass").booleanValue()).isFalse();
	}

	// whatever the error
	@Test
	void criteria_fewerThanThreeRuns_meetsNoCriterion() {
		List<JsonNode> runs = List.of(Runs.passingRun(-0.2), Runs.latest("v5", "a"));

		JsonNode criteria = Tiers.criteria(oneStep(-0.1), runs);

		assertThat(criteria.at("/knee/runs").intValue()).isEqualTo(2);
		assertThat(List.of(criteria.at("/knee/pass"), criteria.at("/p95/pass")))
			.allMatch((pass) -> !pass.booleanValue());
	}

	// the median of 0.1, 0.3 and this run's 0.2; the two disturbed runs are left out
	@Test
	void criteria_disturbedRuns_areNotCounted() {
		JsonNode criteria = Tiers.criteria(disturbedComparison(1.0), runsWithTwoDisturbed());

		assertThat(criteria.get("knee")).isEqualTo(JsonText.read("""
				{"pass": true, "error": 0.2, "runs": 3, "disturbedRuns": 2, "thisRun": 0.2}"""));
	}

	// every run under the protocol still counts as having run on this machine (3b)
	@Test
	void criteria_disturbedRuns_stillCountAsRunOnTheMachine() {
		JsonNode criteria = Tiers.criteria(disturbedComparison(1.0), runsWithTwoDisturbed());

		assertThat(criteria.at("/reproduced/runs").intValue()).isEqualTo(5);
	}

	@Test
	void criteria_aDisturbedLatestRun_isLeftOutToo() {
		ObjectNode worse = disturbedComparison(80.0);
		worse.withArray("rows").add(Runs.step(200, 2.0));

		JsonNode criteria = Tiers.criteria(worse, runsWithTwoDisturbed());

		assertThat(criteria.at("/knee/runs").intValue()).isEqualTo(2);
		assertThat(criteria.at("/knee/disturbedRuns").intValue()).isEqualTo(3);
		assertThat(criteria.at("/knee/pass").booleanValue()).isFalse();
	}

	@Test
	void criteria_aStepTooSlow_missesTheP95Criterion() {
		JsonNode criteria = Tiers.criteria(slowAndUnstable(), List.of(Runs.latest("v5", "a"), Runs.latest("v5", "a")));

		assertThat(criteria.at("/p95/misses")).isEqualTo(Runs.rates(100));
	}

	@Test
	void criteria_aStepUnstable_missesTheStabilityCriterion() {
		JsonNode criteria = Tiers.criteria(slowAndUnstable(), List.of(Runs.latest("v5", "a"), Runs.latest("v5", "a")));

		assertThat(criteria.at("/stable/unstable")).isEqualTo(Runs.rates(200));
	}

	// earlier runs without their checks are not counted
	@Test
	void criteria_aRunWithoutChecks_isNotCounted() {
		JsonNode criteria = Tiers.criteria(slowAndUnstable(), List.of(Runs.latest("v5", "a"), Runs.latest("v5", "a")));

		assertThat(criteria.at("/knee/runs").intValue()).isEqualTo(1);
	}

	@Test
	void criteria_aRunMissingACriterion_doesNotMeetThemAll() {
		JsonNode criteria = Tiers.criteria(slowAndUnstable(), List.of(Runs.latest("v5", "a"), Runs.latest("v5", "a")));

		assertThat(criteria.get("all").booleanValue()).isFalse();
	}

	@Test
	void criteria_runsUnderEarlierProtocols_areNotTheReproduction() {
		List<JsonNode> runs = List.of(Runs.latest("v3", "b"), Runs.latest("v4", "a"));

		JsonNode criteria = Tiers.criteria(oneStep(0.0), runs);

		assertThat(criteria.at("/reproduced/runs").intValue()).isEqualTo(1);
	}

	@Test
	void criteria_withoutAStepBelowTheKnee_meetsNoCriterion() {
		ObjectNode comparison = Runs.comparison(null, null, Runs.row(100, 90.0, 1.0));
		((ObjectNode) comparison.get("measuredKnee")).put("bound", "below");

		JsonNode criteria = Tiers.criteria(comparison, List.of(Runs.latest("v4", "a")));

		assertThat(List.of(criteria.at("/knee/pass"), criteria.at("/p95/pass"), criteria.at("/stable/pass")))
			.allMatch((pass) -> !pass.booleanValue());
	}

	private static ObjectNode passingComparison() {
		return Runs.comparison(-0.2, 250.0, Runs.row(100, 1.0, 2.5), Runs.row(200, 10.0, 15.0),
				Runs.row(300, 80.0, 900.0, true));
	}

	private static List<JsonNode> runsOnTwoMachines() {
		return List.of(Runs.passingRun("b", -0.2), Runs.passingRun(-0.2), Runs.passingRun(-0.2),
				Runs.latest("v5", "a"));
	}

	private static ObjectNode oneStep(double kneeError) {
		return Runs.comparison(kneeError, 150.0, Runs.row(100, 1.0, 1.0));
	}

	// a knee 30% off, a step too slow and another unstable
	private static ObjectNode slowAndUnstable() {
		return Runs.comparison(0.3, 250.0, Runs.row(100, 1.0, 5.0), Runs.row(200, 10.0, 15.0, true));
	}

	// a run at 100 req/s whose measured p95 is given, predicted at 1 ms
	private static ObjectNode disturbedComparison(double measuredP95) {
		ObjectNode step = Runs.step(100, measuredP95);
		step.putObject("predicted").put("p95", 1.0);
		return Runs.comparison(0.2, 150.0, step);
	}

	private static List<JsonNode> runsWithTwoDisturbed() {
		return List.of(disturbed(Runs.passingRun(2.9), 2112), Runs.passingRun(0.1),
				disturbed(Runs.passingRun(2.5), 2550, 3825), Runs.passingRun(0.3), Runs.latest("v5", "a"));
	}

	private static ObjectNode disturbed(ObjectNode run, int... steps) {
		((ObjectNode) run.get("checks")).set("disturbed", Runs.rates(steps));
		return run;
	}

	private static ObjectNode failingP95(ObjectNode run) {
		((ObjectNode) run.get("checks")).put("p95Pass", false);
		return run;
	}

}
