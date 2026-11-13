package io.archsim.benchmarks.report;

import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * How far the measured knee moved between an experiment's kept runs on the latest run's
 * machine, OS build and power mode, and on the same side of any fix to the harness. An OS
 * update is not run-to-run noise (Windows build 26300 moved Kafka's knee from 945 to
 * 2,040 req/s on the same laptop), nor is the power mode (Best performance moved
 * cache-aside's from 4,848 to about 7,000), nor a harness fix (Kafka's broker healthcheck
 * held its knee near 2,100 req/s until 31 Oct, 2,600 to 2,800 after). Every protocol
 * counts: they differ in the calibration, and the knee is found the same way in each.
 * Disturbed runs are left out. The spread's ± is half the range over its middle: 1,000 to
 * 3,000 req/s is ±50%.
 */
final class KneeSpread {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private KneeSpread() {
	}

	/**
	 * The spread of the measured knee over the kept runs alike.
	 * @param runs the kept runs, the latest last
	 * @return the spread; empty with fewer than two such runs that measured a knee
	 */
	static Optional<ObjectNode> of(List<? extends JsonNode> runs) {
		if (runs.isEmpty()) {
			return Optional.empty();
		}
		Conditions where = Conditions.of(runs.getLast());
		List<JsonNode> here = runs.stream()
			.filter((run) -> Conditions.of(run).equals(where))
			.filter((run) -> !PythonValues.isNone(PythonValues.get(run.get("measuredKnee"), "kneeRps")))
			.map(JsonNode.class::cast)
			.toList();
		List<JsonNode> counted = here.stream().filter(RunHistory::counted).toList();
		if (counted.size() < 2) {
			return Optional.empty();
		}
		return Optional.of(spread(counted, here.size() - counted.size()));
	}

	// the counted runs' knees, their range and the half of it over its middle
	private static ObjectNode spread(List<JsonNode> counted, int disturbed) {
		List<JsonNode> knees = counted.stream().map((run) -> run.get("measuredKnee").get("kneeRps")).toList();
		JsonNode low = PythonValues.min(knees);
		JsonNode high = PythonValues.max(knees);
		JsonNode plusMinus = PythonValues.divide(PythonValues.subtract(high, low), PythonValues.add(high, low));
		ObjectNode spread = NODES.objectNode().put("runs", counted.size()).put("disturbed", disturbed);
		spread.set("minRps", low);
		spread.set("maxRps", high);
		spread.set("plusMinus", plusMinus);
		spread.put("text", Figures.rate(low) + " to " + Figures.rate(high) + " req/s");
		spread.put("plusMinusText", "±" + Figures.magnitude(plusMinus));
		ArrayNode values = spread.putArray("values");
		counted.forEach((run) -> {
			ObjectNode value = values.addObject();
			value.set("run", run.get("dir"));
			value.set("protocol", run.get("protocol"));
			value.set("calibratedAt", run.get("calibratedAt"));
			value.set("kneeRps", run.get("measuredKnee").get("kneeRps"));
			value.put("text", Figures.rate(value.get("kneeRps")));
		});
		return spread;
	}

	/**
	 * The conditions a run was measured under.
	 *
	 * @param machine its machine
	 * @param os its OS build
	 * @param powerMode its power mode, or None
	 * @param harnessCaveat whether a harness fix had yet to come
	 */
	private record Conditions(JsonNode machine, JsonNode os, JsonNode powerMode, boolean harnessCaveat) {

		static Conditions of(JsonNode run) {
			return new Conditions(PythonValues.get(run, "machine"), PythonValues.get(run, "os"),
					PythonValues.get(run, "powerMode"), PythonText.truthy(PythonValues.get(run, "harnessCaveat")));
		}

	}

}
