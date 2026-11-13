package io.archsim.benchmarks.comparison;

import java.util.Arrays;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

class KneesTests {

	private static final ObjectNode RANGE = JsonText
		.object("{\"lowRps\": 900, \"highRps\": 1100, \"nominalCoverage\": 0.5, \"replicates\": 3}");

	// pg-mixed's second run of 30 October: missed at
	// 2,550 to 4,590 req/s, met at 5,100, missed above
	@Test
	void disturbedSteps_missesBelowAStepThatMet_areDisturbed() {
		List<JsonNode> rows = List.of(measured(1275, 8.5, 1.0), measured(2550, 102.1, 0.927),
				measured(3825, 119.6, 0.798), measured(4590, 190.3, 0.737), measured(5100, 46.6, 0.998),
				measured(5610, 136.7, 0.858));

		List<JsonNode> disturbed = Knees.disturbedSteps(rows);

		assertThat(disturbed).extracting(JsonNode::intValue).containsExactly(2550, 3825, 4590);
	}

	@Test
	void disturbedSteps_aMissOnTheSuccessRateAlone_isDisturbed() {
		List<JsonNode> rows = List.of(measured(100, 2.0, 1.0), measured(200, 9.0, 0.9), measured(300, 9.0, 1.0));

		List<JsonNode> disturbed = Knees.disturbedSteps(rows);

		assertThat(disturbed).extracting(JsonNode::intValue).containsExactly(200);
	}

	@Test
	void disturbedSteps_rowsInAnyOrder_areReadByLoad() {
		List<JsonNode> rows = List.of(measured(300, 9.0, 1.0), measured(200, 60.0, 1.0), measured(100, 2.0, 1.0));

		List<JsonNode> disturbed = Knees.disturbedSteps(rows);

		assertThat(disturbed).extracting(JsonNode::intValue).containsExactly(200);
	}

	@Test
	void disturbedSteps_aStackThatStopsMeetingTheObjective_isNotDisturbed() {
		List<JsonNode> rows = List.of(measured(100, 2.0, 1.0), measured(200, 60.0, 1.0), measured(300, null, 0.4));

		List<JsonNode> disturbed = Knees.disturbedSteps(rows);

		assertThat(disturbed).isEmpty();
	}

	@Test
	void disturbedSteps_aStackThatNeverMet_isNotDisturbed() {
		List<JsonNode> rows = List.of(measured(100, 90.0, 1.0), measured(200, 95.0, 1.0));

		List<JsonNode> disturbed = Knees.disturbedSteps(rows);

		assertThat(disturbed).isEmpty();
	}

	// older tests' rows have no success rate, so they never meet the objective
	@Test
	void disturbedSteps_rowsWithoutASuccessRate_areNotDisturbed() {
		ObjectNode slow = JsonText.object("{\"offeredRps\": 100, \"measured\": {\"p95\": 99.0}}");
		ObjectNode fast = JsonText.object("{\"offeredRps\": 200, \"measured\": {\"p95\": 1.0}}");

		List<JsonNode> disturbed = Knees.disturbedSteps(List.of(slow, fast));

		assertThat(disturbed).isEmpty();
	}

	@Test
	void gridKnee_p95CrossingTheObjective_isInterpolatedInP95() {
		JsonNode knee = Knees
			.gridKnee(List.of(Repeats.step(300, 70.0), Repeats.step(100, 10.0), Repeats.step(200, 30.0)));

		assertThat(knee).isEqualTo(JsonText.read("""
				{"kneeRps": 250.0, "bound": "interpolated", "between": [200, 300]}"""));
	}

	@Test
	void gridKnee_successFallingBelowTheObjective_isInterpolatedInSuccess() {
		JsonNode knee = Knees.gridKnee(List.of(Repeats.step(100, 5.0), Repeats.step(200, 5.0, 0.97)));

		assertThat(knee.get("kneeRps").doubleValue()).isEqualTo(133.3);
	}

	// p95 crosses 50 ms at 150 req/s, and success 99% at 150 req/s too: (1-0.99)/(1-0.98)
	@Test
	void gridKnee_bothFailing_takesTheLowerCrossing() {
		JsonNode knee = Knees.gridKnee(List.of(Repeats.step(100, 10.0), Repeats.step(200, 90.0, 0.98)));

		assertThat(knee.get("kneeRps").doubleValue()).isEqualTo(150.0);
	}

	@Test
	void gridKnee_failingStepWithoutLatency_isTheLastStepThatMet() {
		JsonNode knee = Knees.gridKnee(List.of(Repeats.step(100, 5.0), Repeats.step(200, null, 0.0)));

		assertThat(knee).isEqualTo(JsonText.read("""
				{"kneeRps": 100, "bound": "interpolated", "between": [100, 200]}"""));
	}

	@Test
	void gridKnee_everyStepMissing_isBelowTheLadder() {
		JsonNode knee = Knees.gridKnee(List.of(Repeats.step(100, 60.0), Repeats.step(200, 70.0)));

		assertThat(knee).isEqualTo(JsonText.read("{\"kneeRps\": null, \"bound\": \"below\", \"belowRps\": 100}"));
	}

	@Test
	void gridKnee_everyStepMeeting_isAboveTheLadder() {
		JsonNode knee = Knees.gridKnee(List.of(Repeats.step(100, 5.0), Repeats.step(200, 6.0)));

		assertThat(knee).isEqualTo(JsonText.read("{\"kneeRps\": null, \"bound\": \"above\", \"aboveRps\": 200}"));
	}

	@Test
	void atOrBelowKnee_aKnee_isTheStepsUpToIt() {
		List<JsonNode> rows = rows(100, 200);

		List<JsonNode> below = Knees.atOrBelowKnee(rows, JsonText.read("{\"kneeRps\": 150.0}"));

		assertThat(below).containsExactly(rows.getFirst());
	}

	@Test
	void atOrBelowKnee_aboveTheLadder_isEveryStep() {
		List<JsonNode> rows = rows(100, 200);

		List<JsonNode> below = Knees.atOrBelowKnee(rows, JsonText.read("{\"kneeRps\": null, \"bound\": \"above\"}"));

		assertThat(below).isEqualTo(rows);
	}

	@Test
	void atOrBelowKnee_belowTheLadder_isNoStep() {
		List<JsonNode> below = Knees.atOrBelowKnee(rows(100, 200),
				JsonText.read("{\"kneeRps\": null, \"bound\": \"below\"}"));

		assertThat(below).isEmpty();
	}

	// the model's knee is the lower one: past it the model's queue grows without bound
	@Test
	void atOrBelowBothKnees_theModelsKneeLower_isTheStepsUpToTheModelsKnee() {
		List<JsonNode> rows = rows(100, 200, 300);

		List<JsonNode> below = Knees.atOrBelowBothKnees(rows, knee(250.0), knee(150.0));

		assertThat(below).containsExactly(rows.getFirst());
	}

	@Test
	void atOrBelowBothKnees_theMeasuredKneeLower_isTheStepsUpToTheMeasuredKnee() {
		List<JsonNode> rows = rows(100, 200, 300);

		List<JsonNode> below = Knees.atOrBelowBothKnees(rows, knee(150.0), knee(250.0));

		assertThat(below).containsExactly(rows.getFirst());
	}

	@Test
	void atOrBelowBothKnees_measuredAboveTheLadder_isTheStepsUpToThePredictedKnee() {
		List<JsonNode> rows = rows(100, 200, 300);
		JsonNode above = JsonText.read("{\"kneeRps\": null, \"bound\": \"above\"}");

		List<JsonNode> below = Knees.atOrBelowBothKnees(rows, above, JsonText.read("{\"kneeRps\": 250.0}"));

		assertThat(below).isEqualTo(rows.subList(0, 2));
	}

	@Test
	void inRange_aMeasuredKneeInside_isTrue() {
		assertThat(Knees.inRange(RANGE, JsonText.read("{\"kneeRps\": 950.0}"))).contains(true);
	}

	@Test
	void inRange_aMeasuredKneeOutside_isFalse() {
		assertThat(Knees.inRange(RANGE, JsonText.read("{\"kneeRps\": 1200.0}"))).contains(false);
	}

	// a knee above the ladder is inside when the range reaches past the top step
	@Test
	void inRange_aKneeAboveTheLadderWithinTheRange_isInside() {
		JsonNode above = JsonText.read("{\"kneeRps\": null, \"bound\": \"above\", \"aboveRps\": 1050}");

		assertThat(Knees.inRange(RANGE, above)).contains(true);
	}

	@Test
	void inRange_aKneeAboveTheLadderPastTheRange_isOutside() {
		JsonNode above = JsonText.read("{\"kneeRps\": null, \"bound\": \"above\", \"aboveRps\": 1200}");

		assertThat(Knees.inRange(RANGE, above)).contains(false);
	}

	// a knee below the ladder is inside when the range starts below the first step
	@Test
	void inRange_aKneeBelowTheLadderWithinTheRange_isInside() {
		JsonNode below = JsonText.read("{\"kneeRps\": null, \"bound\": \"below\", \"belowRps\": 950}");

		assertThat(Knees.inRange(RANGE, below)).contains(true);
	}

	@Test
	void inRange_aKneeBelowTheLadderBeforeTheRange_isOutside() {
		JsonNode below = JsonText.read("{\"kneeRps\": null, \"bound\": \"below\", \"belowRps\": 800}");

		assertThat(Knees.inRange(RANGE, below)).contains(false);
	}

	@Test
	void atOrBelowBothKnees_bothAboveTheLadder_isEveryStep() {
		List<JsonNode> rows = rows(100, 200, 300);
		JsonNode above = JsonText.read("{\"kneeRps\": null, \"bound\": \"above\"}");

		List<JsonNode> below = Knees.atOrBelowBothKnees(rows, above, above);

		assertThat(below).isEqualTo(rows);
	}

	@Test
	void inRange_withoutARange_isEmpty() {
		assertThat(Knees.inRange(JsonText.read("null"), JsonText.read("{\"kneeRps\": 950.0}"))).isEmpty();
	}

	// a comparison's row with what the objective reads
	private static ObjectNode measured(int rate, @Nullable Double p95, double successRate) {
		ObjectNode row = JsonText.object("{\"measured\": {}}").put("offeredRps", rate);
		ObjectNode measured = (ObjectNode) row.get("measured");
		if (p95 == null) {
			measured.putNull("p95");
		}
		else {
			measured.put("p95", p95);
		}
		measured.put("successRate", successRate);
		return row;
	}

	private static JsonNode knee(double rate) {
		return JsonText.read("{\"kneeRps\": " + rate + "}");
	}

	private static List<JsonNode> rows(int... rates) {
		return Arrays.stream(rates).<JsonNode>mapToObj((rate) -> Repeats.step(rate, 1.0)).toList();
	}

}
