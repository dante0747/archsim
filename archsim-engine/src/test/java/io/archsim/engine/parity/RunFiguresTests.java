package io.archsim.engine.parity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.report.ReportDigest;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.parity.SchemaDepartures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a run keeps of its report, as the baselines' Python script keeps it (its tests use
 * the same report).
 */
class RunFiguresTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNode REPORT = JSON.readTree("""
			{"provenance": {"eventsProcessed": 120, "requestsGenerated": 40, "inputDigest": "in",
			                "resultDigest": "out", "wallSeconds": 1.5},
			 "summary": {"arrived": 40, "successRate": 1.0, "latencyMs": {"p50": 3.0, "p95": 9.0},
			             "latencyWithFailuresMs": {"p50": 3.0}, "clients": [{"id": "users"}],
			             "accounting": {"arrived": 40}, "capacityLimit": null},
			 "nodes": [{"id": "api", "summary": {"utilization": 0.5, "capacityLimit": "cpu", "hitRatio": null}},
			           {"id": "users", "summary": {"arrivals": 40, "byClient": {"a": 1}}}]}""");

	/**
	 * A design of the frozen engine's as the Java reads it: at its schema, pinned to
	 * whole cores.
	 */
	private static final JsonNode PINNED = JSON.readTree("""
			{"schemaVersion": "%s", "architecture": {"nodes": [{"id": "api"}], "siblingSpeed": 1}}"""
		.formatted(ArchitectureDocument.SCHEMA_VERSION));

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

	@Test
	void of_aRunOfADesignAtALaterSchemaPinnedToWholeCores_keepsTheDigestOfTheDesignTheFrozenEngineWasGiven() {
		JsonNode given = JSON.readTree("""
				{"schemaVersion": "%s", "architecture": {"nodes": [{"id": "api"}]}}"""
			.formatted(SchemaDepartures.RECORDED));

		JsonNode figures = RunFigures.of(withInput(PINNED, ReportDigest.of(PINNED)));

		assertThat(figures.get("inputDigest").stringValue()).isEqualTo(ReportDigest.of(given));
	}

	@Test
	void of_aRunWhoseInputDigestIsNotItsInputs_keepsTheDigestAsItIs() {
		JsonNode figures = RunFigures.of(withInput(PINNED, "another"));

		assertThat(figures.get("inputDigest").stringValue()).isEqualTo("another");
	}

	// the report, run on the input given, with the input digest given
	private static JsonNode withInput(JsonNode input, String inputDigest) {
		ObjectNode report = (ObjectNode) REPORT.deepCopy();
		report.set("input", input);
		((ObjectNode) report.get("provenance")).put("inputDigest", inputDigest);
		return report;
	}

}
