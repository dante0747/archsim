package io.archsim.engine.parity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a run keeps of its report, as the baselines' Python script keeps it (its tests use
 * the same report).
 */
class RunFiguresTests {

	private static final JsonNode REPORT = JsonMapper.builder().build().readTree("""
			{"provenance": {"eventsProcessed": 120, "requestsGenerated": 40, "inputDigest": "in",
			                "resultDigest": "out", "wallSeconds": 1.5},
			 "summary": {"arrived": 40, "successRate": 1.0, "latencyMs": {"p50": 3.0, "p95": 9.0},
			             "latencyWithFailuresMs": {"p50": 3.0}, "clients": [{"id": "users"}],
			             "accounting": {"arrived": 40}, "capacityLimit": null},
			 "nodes": [{"id": "api", "summary": {"utilization": 0.5, "capacityLimit": "cpu", "hitRatio": null}},
			           {"id": "users", "summary": {"arrivals": 40, "byClient": {"a": 1}}}]}""");

	@Test
	void of_aReport_keepsTheCountsAndDigestsButNotTheWallTime() {
		JsonNode figures = RunFigures.of(REPORT);

		assertThat(figures.toString()).startsWith(
				"{\"eventsProcessed\":120,\"requestsGenerated\":40,\"inputDigest\":\"in\",\"resultDigest\":\"out\",");
	}

	@Test
	void of_aReport_keepsTheSummarysValuesAndLatenciesNotItsListsOrNones() {
		JsonNode summary = RunFigures.of(REPORT).get("summary");

		assertThat(summary.toString())
			.isEqualTo("{\"arrived\":40,\"successRate\":1.0,\"latencyMs\":{\"p50\":3.0,\"p95\":9.0},"
					+ "\"latencyWithFailuresMs\":{\"p50\":3.0}}");
	}

	@Test
	void of_aReport_keepsEachPartsSummaryValuesByItsId() {
		JsonNode nodes = RunFigures.of(REPORT).get("nodes");

		assertThat(nodes.toString())
			.isEqualTo("{\"api\":{\"utilization\":0.5,\"capacityLimit\":\"cpu\"},\"users\":{\"arrivals\":40}}");
	}

}
