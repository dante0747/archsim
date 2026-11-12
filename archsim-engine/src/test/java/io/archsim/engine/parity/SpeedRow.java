package io.archsim.engine.parity;

import java.util.Collections;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A template's timing as the since-deleted {@code scripts/perf.py} wrote one, so the
 * frozen engine's and the Java simulator's files read alike: the requests and events of
 * the run, each wall time, the best, the rates at the best and the result's digest.
 */
final class SpeedRow {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final double MILLIS_PER_SECOND = 1000.0;

	private SpeedRow() {
	}

	/**
	 * A template's row, with {@code perf.py}'s fields: besides the timing, the run's
	 * mode, its p95 and its throughput.
	 * @param template the template's file name, without {@code .json}
	 * @param design its design
	 * @param report the report of one of its runs
	 * @param walls each run's wall time, in seconds; at least one
	 * @return the row
	 */
	static ObjectNode of(String template, JsonNode design, JsonNode report, List<Double> walls) {
		JsonNode provenance = report.path("provenance");
		long requests = provenance.path("requestsGenerated").longValue();
		long events = provenance.path("eventsProcessed").longValue();
		double best = Collections.min(walls);
		ObjectNode row = JSON.createObjectNode()
			.put("template", template)
			.put("name", design.path("metadata").path("name").stringValue())
			.put("requests", requests)
			.put("events", events);
		ArrayNode wallSeconds = row.putArray("wallSeconds");
		walls.forEach((wall) -> wallSeconds.add(toTheMillisecond(wall)));
		row.put("bestWallSeconds", toTheMillisecond(best))
			.put("requestsPerSecond", Math.round(requests / best))
			.put("eventsPerSecond", Math.round(events / best))
			.set("resultDigest", copied(provenance.path("resultDigest")));
		JsonNode mode = report.path("simulationMode").path("kind");
		row.put("mode", mode.isString() ? mode.stringValue() : "full");
		row.set("p95", copied(report.path("summary").path("latencyMs").path("p95")));
		row.set("throughputRps", copied(report.path("summary").path("throughputRps")));
		return row;
	}

	// a report's value, or null where it has none
	private static JsonNode copied(JsonNode value) {
		return value.isMissingNode() ? JSON.nullNode() : value.deepCopy();
	}

	private static double toTheMillisecond(double seconds) {
		return Math.round(seconds * MILLIS_PER_SECOND) / MILLIS_PER_SECOND;
	}

}
