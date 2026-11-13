package io.archsim.benchmarks.parity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;

/**
 * Each template's median wall time on both engines, over every run each timed, and
 * whether they gave the same result. A template the Java run did not time is kept,
 * unmeasured.
 */
final class SpeedRows {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final int SPEED_UP_DECIMALS = 1;

	private SpeedRows() {
	}

	/**
	 * The templates the frozen engine timed, against the Java engine's times.
	 * @param python the frozen engine's speed file
	 * @param java the Java engine's speed file
	 * @return a row per template, in the frozen engine's order
	 */
	static List<ObjectNode> of(JsonNode python, JsonNode java) {
		Map<String, JsonNode> javaRows = new HashMap<>();
		java.get("templates").forEach((row) -> javaRows.put(row.get("template").stringValue(), row));
		List<ObjectNode> rows = new ArrayList<>();
		for (JsonNode row : python.get("templates")) {
			JsonNode other = javaRows.get(row.get("template").stringValue());
			JsonNode pythonMedian = median(row);
			JsonNode javaMedian = (other != null) ? median(other) : PythonValues.none();
			ObjectNode speed = NODES.objectNode().set("template", row.get("template"));
			speed.set("requests", row.get("requests"));
			speed.putArray("runs")
				.add(row.get("wallSeconds").size())
				.add((other != null) ? other.get("wallSeconds").size() : 0);
			speed.set("python", pythonMedian);
			speed.set("java", javaMedian);
			speed.set("speedUp",
					(PythonValues.isNone(javaMedian) || javaMedian.doubleValue() == 0) ? PythonValues.none()
							: PythonValues.round(PythonValues.divide(pythonMedian, javaMedian), SPEED_UP_DECIMALS));
			speed.put("sameResult", other != null && row.get("resultDigest").equals(other.get("resultDigest")));
			rows.add(speed);
		}
		return rows;
	}

	private static JsonNode median(JsonNode timed) {
		return PythonValues.median(timed.get("wallSeconds").valueStream().toList());
	}

}
