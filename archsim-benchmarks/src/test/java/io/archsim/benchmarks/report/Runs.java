package io.archsim.benchmarks.report;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Comparisons and kept runs for the report's tests, as {@code comparison.json} and the
 * run history hold them.
 */
final class Runs {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private Runs() {
	}

	/**
	 * A comparison of these rows, with a knee error and a measured knee.
	 */
	static ObjectNode comparison(@Nullable Double kneeError, @Nullable Double measuredKnee, JsonNode... rows) {
		ObjectNode comparison = NODES.objectNode();
		putNumber(comparison, "kneeError", kneeError);
		putNumber(comparison.putObject("measuredKnee"), "kneeRps", measuredKnee);
		comparison.putArray("rows").addAll(List.of(rows));
		return comparison;
	}

	/**
	 * A comparison's row with a measured and a predicted p95.
	 */
	static ObjectNode row(int rate, @Nullable Double measured, @Nullable Double predicted, boolean unstable) {
		ObjectNode row = NODES.objectNode().put("offeredRps", rate);
		putNumber(row.putObject("measured"), "p95", measured);
		putNumber(row.putObject("predicted"), "p95", predicted);
		return row.put("unstable", unstable);
	}

	static ObjectNode row(int rate, @Nullable Double measured, @Nullable Double predicted) {
		return row(rate, measured, predicted, false);
	}

	/**
	 * A comparison's row with what the objective reads: p95 at most 50 ms, success at
	 * least 99%.
	 */
	static ObjectNode step(int rate, @Nullable Double p95, double successRate) {
		ObjectNode row = NODES.objectNode().put("offeredRps", rate);
		ObjectNode measured = row.putObject("measured");
		putNumber(measured, "p95", p95);
		measured.put("successRate", successRate);
		return row;
	}

	static ObjectNode step(int rate, @Nullable Double p95) {
		return step(rate, p95, 1.0);
	}

	/**
	 * A kept run under protocol v5, which met criteria 2 and 3a.
	 */
	static ObjectNode passingRun(String machine, double error) {
		ObjectNode run = NODES.objectNode().put("protocol", "v5").put("machine", machine);
		ObjectNode checks = run.putObject("checks").put("kneeError", error).put("steps", 2);
		checks.putArray("misses");
		checks.putArray("unstable");
		checks.put("p95Pass", true).put("stablePass", true);
		return run.put("kneeError", error);
	}

	static ObjectNode passingRun(double error) {
		return passingRun("a", error);
	}

	/**
	 * A kept run without its checks: the latest, which the comparison describes.
	 */
	static ObjectNode latest(String protocol, String machine) {
		return NODES.objectNode().put("protocol", protocol).put("machine", machine);
	}

	/**
	 * Loads, as a JSON array.
	 */
	static ArrayNode rates(int... rates) {
		ArrayNode array = NODES.arrayNode();
		for (int rate : rates) {
			array.add(rate);
		}
		return array;
	}

	private static void putNumber(ObjectNode object, String key, @Nullable Double value) {
		if (value == null) {
			object.putNull(key);
		}
		else {
			object.put(key, value);
		}
	}

}
