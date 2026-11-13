package io.archsim.benchmarks.comparison;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.values.PythonValues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ComparisonTests {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final Experiment PG_MIXED = Experiments.standard().get("pg-mixed");

	private static final double CLOSE = 1e-9;

	private static final JsonNode NONE = PythonValues.none();

	@Test
	void compare_aLadder_comparesEachStepsP95WithItsPrediction() {
		ObjectNode comparison = Comparison.compare(PG_MIXED, predictions(), measured(), NONE);

		JsonNode rows = rowsByRate(comparison);
		assertThat(rows.at("/100/measured/p95").doubleValue()).isEqualTo(2.2);
		assertThat(rows.at("/100/error/p95").doubleValue()).isCloseTo(1.0, within(CLOSE));
		assertThat(rows.at("/300/error/p95").doubleValue()).isCloseTo(-0.5, within(CLOSE));
	}

	@Test
	void compare_aStepMostRepeatsFailed_isUnstableWithoutALatencyOrError() {
		ObjectNode comparison = Comparison.compare(PG_MIXED, predictions(), measured(), NONE);

		JsonNode step = rowsByRate(comparison).get("400");
		assertThat(List.of(step.at("/measured/p95"), step.at("/error/p95"))).allMatch(JsonNode::isNull);
		assertThat(step.get("failedRepeats").intValue()).isEqualTo(2);
		assertThat(step.get("repeats").intValue()).isEqualTo(3);
		assertThat(step.get("unstable").booleanValue()).isTrue();
	}

	@Test
	void compare_aLadder_findsBothKneesOnTheSameSteps() {
		ObjectNode comparison = Comparison.compare(PG_MIXED, predictions(), measured(), NONE);

		assertThat(comparison.at("/measuredKnee/kneeRps").doubleValue()).isEqualTo(300.0);
		assertThat(comparison.at("/predictedKnee/kneeRps").doubleValue()).isEqualTo(349.2);
		assertThat(comparison.get("kneeError").doubleValue()).isCloseTo(349.2 / 300 - 1, within(CLOSE));
		assertThat(comparison.at("/predictedKneeFine").intValue()).isEqualTo(340);
	}

	// at or below the knee: errors 0.2, 1.0, 0.1 and 0.5, whose median is 0.35
	@Test
	void compare_aLadder_summarisesTheP95ErrorsAtOrBelowTheMeasuredKnee() {
		ObjectNode comparison = Comparison.compare(PG_MIXED, predictions(), measured(), NONE);

		assertThat(comparison.at("/belowKnee/steps").intValue()).isEqualTo(4);
		assertThat(comparison.at("/belowKnee/medianAbsP95Error").doubleValue()).isCloseTo(0.35, within(CLOSE));
		assertThat(comparison.at("/belowKnee/maxAbsP95Error").doubleValue()).isCloseTo(1.0, within(CLOSE));
	}

	@Test
	void compare_aPredictedRange_saysWhetherItHeldTheMeasuredKnee() {
		ObjectNode predictions = predictions();
		predictions.set("kneeRange", JsonText.read("{\"lowRps\": 280, \"highRps\": 400, \"nominalCoverage\": 0.8}"));

		ObjectNode comparison = Comparison.compare(PG_MIXED, predictions, measured(), NONE);

		assertThat(comparison.get("kneeRange")).isEqualTo(JsonText
			.read("{\"lowRps\": 280, \"highRps\": 400, \"nominalCoverage\": 0.8, \"containsMeasured\": true}"));
	}

	@Test
	void compare_predictionsWithResources_comparesEachContainer() {
		ObjectNode comparison = Comparison.compare(PG_MIXED, predictionsWithResources(), measuredWithResources(), NONE);

		JsonNode api = rowsByRate(comparison).at("/100/resources/api");
		assertThat(api.get("measured")).isEqualTo(JsonText.read("{\"cores\": 0.2, \"memoryMiB\": 100.0}"));
		assertThat(api.get("predicted")).isEqualTo(JsonText.read("{\"cores\": 0.4, \"memoryMiB\": 260.0}"));
		assertThat(api.at("/error/cores").doubleValue()).isCloseTo(1.0, within(CLOSE));
		assertThat(api.at("/error/memory").doubleValue()).isCloseTo(1.6, within(CLOSE));
		assertThat(comparison.at("/resources/measuredBy").stringValue()).isEqualTo("window");
		assertThat(comparison.at("/resources/predictedBy").stringValue()).isEqualTo("predictions");
	}

	// the four steps at or below the 300 req/s knee
	@Test
	void compare_predictionsWithResources_summarisesTheErrorsAtOrBelowBothKnees() {
		JsonNode below = Comparison.compare(PG_MIXED, predictionsWithResources(), measuredWithResources(), NONE)
			.at("/resources/belowKnee");

		assertThat(below.at("/api/steps").intValue()).isEqualTo(4);
		assertThat(below.at("/api/medianAbsCoresError").doubleValue()).isCloseTo(1.0, within(CLOSE));
		assertThat(below.at("/postgres/medianAbsCoresError").doubleValue()).isCloseTo(0.0, within(CLOSE));
		assertThat(below.at("/postgres/medianAbsMemoryError").isNull()).isTrue();
	}

	@Test
	void compare_resourcesFromALaterSimulation_namesTheSimulation() {
		ObjectNode comparison = Comparison.compare(PG_MIXED, predictions(), measuredAtHalfACore(), laterSimulation());

		assertThat(comparison.at("/resources/predictedBy")).isEqualTo(JsonText
			.read("{\"engineVersion\": \"9.9.9\", \"at\": \"2026-10-28T00:00:00Z\", \"how\": \"simulated again\"}"));
	}

	@Test
	void compare_resourcesFromALaterSimulation_comparesEachContainerWithIt() {
		ObjectNode comparison = Comparison.compare(PG_MIXED, predictions(), measuredAtHalfACore(), laterSimulation());

		assertThat(comparison.at("/rows/0/resources/api/error/cores").doubleValue()).isCloseTo(-0.5, within(CLOSE));
		assertThat(comparison.at("/rows/0/resources/postgres/predicted/cores").isNull()).isTrue();
	}

	// as for the older kept runs
	@Test
	void compare_withoutAnyPredictionOfResources_comparesLatencyAlone() {
		ObjectNode comparison = Comparison.compare(PG_MIXED, predictions(), measuredAtHalfACore(), NONE);

		assertThat(comparison.has("resources")).isFalse();
		assertThat(comparison.get("rows")).noneMatch((row) -> row.has("resources"));
	}

	// a later simulation that put the service at a quarter of a core at every step
	private static ObjectNode laterSimulation() {
		ObjectNode later = JsonText.object("""
				{"engineVersion": "9.9.9", "at": "2026-10-28T00:00:00Z", "how": "simulated again", "steps": []}""");
		predictions().get("steps")
			.forEach((step) -> later.withArray("steps")
				.add(NODES.objectNode()
					.put("offeredRps", step.get("offeredRps").intValue())
					.set("resources", JsonText.read("{\"api\": {\"cores\": 0.25}}"))));
		return later;
	}

	private static ObjectNode measuredAtHalfACore() {
		ObjectNode measured = measured();
		measured.get("steps").forEach((step) -> withResources(step, 0.5));
		return measured;
	}

	private static ObjectNode predictions() {
		ObjectNode predictions = JsonText.object("{\"knee\": {\"kneeRps\": 340}, \"steps\": []}");
		ArrayNode steps = predictions.withArray("steps");
		int[] rates = { 50, 100, 200, 300, 400 };
		double[] p95s = { 1.2, 4.4, 3.3, 21.0, 80.0 };
		for (int i = 0; i < rates.length; i++) {
			steps.add(NODES.objectNode()
				.put("offeredRps", rates[i])
				.put("p50", p95s[i] / 2)
				.put("p95", p95s[i])
				.put("p99", p95s[i] * 2)
				.put("successRate", 1.0)
				.put("throughputRps", rates[i]));
		}
		return predictions;
	}

	// k6's zeros for two failed repeats at 400 req/s, as run 4 recorded them
	private static ObjectNode measured() {
		ObjectNode measured = JsonText.object("{\"steps\": []}");
		ArrayNode steps = measured.withArray("steps");
		steps.add(step(50, Repeats.repeat(1.0), Repeats.repeat(1.0), Repeats.repeat(1.0)));
		steps.add(step(100, Repeats.repeat(2.0), Repeats.repeat(2.2), Repeats.repeat(2.4)));
		steps.add(step(200, Repeats.repeat(3.0), Repeats.repeat(3.0), Repeats.repeat(3.0)));
		steps.add(step(300, Repeats.repeat(40.0), Repeats.repeat(45.0), Repeats.repeat(42.0)));
		steps.add(
				step(400, Repeats.repeat(100.0), Repeats.repeat(null).put("p95", 0), Repeats.repeat(null).put("p95", 0))
					.put("p95", 0));
		return measured;
	}

	private static ObjectNode predictionsWithResources() {
		ObjectNode predictions = predictions();
		for (int i = 0; i < predictions.get("steps").size(); i++) {
			ObjectNode resources = ((ObjectNode) predictions.get("steps").get(i)).putObject("resources");
			resources.putObject("api").put("cores", 0.2 * (i + 1)).put("memoryMiB", 260.0);
			resources.putObject("db").put("cores", 0.1);
		}
		return predictions;
	}

	// the service's busy cores rising 0.1 a step
	private static ObjectNode measuredWithResources() {
		ObjectNode measured = measured();
		for (int i = 0; i < measured.get("steps").size(); i++) {
			withResources(measured.get("steps").get(i), 0.1 * (i + 1));
		}
		return measured;
	}

	private static ObjectNode step(int rate, ObjectNode... runs) {
		ObjectNode step = NODES.objectNode().put("offeredRps", rate);
		step.putArray("runs").addAll(List.of(runs));
		return step;
	}

	private static void withResources(JsonNode step, double apiCores) {
		for (JsonNode run : step.get("runs")) {
			ObjectNode containers = JsonText.object("{\"postgres\": {\"cores\": 0.1}}");
			containers.putObject("api").put("cores", apiCores).put("workingSetMiB", 100.0);
			((ObjectNode) run).putObject("resources").put("method", "window").set("containers", containers);
		}
	}

	private static JsonNode rowsByRate(JsonNode comparison) {
		ObjectNode rows = NODES.objectNode();
		comparison.get("rows").forEach((row) -> rows.set(row.get("offeredRps").asString(), row));
		return rows;
	}

}
