package io.archsim.engine.parity;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A template's timing, in the shape the since-deleted {@code scripts/perf.py} wrote, so
 * the two engines' files read alike.
 */
class SpeedRowTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNode DESIGN = JSON.readTree("""
			{"metadata": {"name": "Queueing lab"}}""");

	private static final JsonNode REPORT = JSON.readTree("""
			{"provenance": {"requestsGenerated": 24000, "eventsProcessed": 300000, "resultDigest": "d"},
			 "summary": {"latencyMs": {"p95": 12.5}, "throughputRps": 199.8}}""");

	@Test
	void of_aFullRun_keepsItsModeP95AndThroughput() {
		JsonNode row = SpeedRow.of("queueing-lab", DESIGN, REPORT, List.of(0.3));

		assertThat(List.of(row.get("mode").stringValue(), String.valueOf(row.get("p95").doubleValue()),
				String.valueOf(row.get("throughputRps").doubleValue())))
			.containsExactly("full", "12.5", "199.8");
	}

	@Test
	void of_aSampledRun_saysSo() {
		JsonNode sampled = JSON.readTree("""
				{"simulationMode": {"kind": "sampled"}, "summary": {},
				 "provenance": {"requestsGenerated": 10, "eventsProcessed": 20}}""");

		assertThat(SpeedRow.of("queueing-lab", DESIGN, sampled, List.of(0.3)).get("mode").stringValue())
			.isEqualTo("sampled");
	}

	@Test
	void of_threeRuns_takesTheBestAndItsRates() {
		JsonNode row = SpeedRow.of("queueing-lab", DESIGN, REPORT, List.of(0.31, 0.24, 0.3));

		assertThat(List.of(row.get("bestWallSeconds").doubleValue(), row.get("requestsPerSecond").doubleValue(),
				row.get("eventsPerSecond").doubleValue()))
			.containsExactly(0.24, 100_000.0, 1_250_000.0);
	}

	@Test
	void of_aRun_keepsEachWallTimeToTheMillisecond() {
		JsonNode row = SpeedRow.of("queueing-lab", DESIGN, REPORT, List.of(0.31234, 0.2449));

		assertThat(row.get("wallSeconds").valueStream().map(JsonNode::doubleValue)).containsExactly(0.312, 0.245);
	}

	@Test
	void of_aRun_namesItsTemplateAndItsResult() {
		JsonNode row = SpeedRow.of("queueing-lab", DESIGN, REPORT, List.of(0.3));

		assertThat(List.of(row.get("template").stringValue(), row.get("name").stringValue(),
				row.get("resultDigest").stringValue(), String.valueOf(row.get("requests").longValue())))
			.containsExactly("queueing-lab", "Queueing lab", "d", "24000");
	}

}
