package io.archsim.engine.parity;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.report.ReportDigest;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.parity.JsonDiff;
import io.archsim.parity.SchemaDepartures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a run keeps of its report, as the baselines' Python script keeps it (its tests use
 * the same report), and the Java's figures read as the frozen engine's: on the golden
 * files' runs of the templates, against what the script would have kept of the frozen
 * engine's report of the same run.
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
	void kept_aReport_keepsTheCountsAndDigestsButNotTheWallTime() {
		JsonNode figures = RunFigures.kept(REPORT);

		assertThat(figures.toString()).startsWith(
				"{\"eventsProcessed\":120,\"requestsGenerated\":40,\"inputDigest\":\"in\",\"resultDigest\":\"out\",");
	}

	@Test
	void kept_aReport_keepsTheSummarysValuesAndLatenciesNotItsListsOrNones() {
		JsonNode summary = RunFigures.kept(REPORT).get("summary");

		assertThat(summary.toString())
			.isEqualTo("{\"arrived\":40,\"successRate\":1.0,\"latencyMs\":{\"p50\":3.0,\"p95\":9.0},"
					+ "\"latencyWithFailuresMs\":{\"p50\":3.0}}");
	}

	@Test
	void kept_aReport_keepsEachPartsSummaryValuesByItsId() {
		JsonNode nodes = RunFigures.kept(REPORT).get("nodes");

		assertThat(nodes.toString())
			.isEqualTo("{\"api\":{\"utilization\":0.5,\"capacityLimit\":\"cpu\"},\"users\":{\"arrivals\":40}}");
	}

	@Test
	void of_aRunOfADesignAtALaterSchemaPinnedToWholeCores_keepsTheDigestOfTheDesignTheFrozenEngineWasGiven() {
		JsonNode given = JSON.readTree("""
				{"schemaVersion": "%s", "architecture": {"nodes": [{"id": "api"}]}}"""
			.formatted(SchemaDepartures.RECORDED));

		JsonNode figures = RunFigures.of(withInput(PINNED, ReportDigest.of(PINNED)), RunFigures.kept(REPORT),
				MissingNode.getInstance());

		assertThat(figures.get("inputDigest").stringValue()).isEqualTo(ReportDigest.of(given));
	}

	@Test
	void of_aRunWhoseInputDigestIsNotItsInputs_keepsTheDigestAsItIs() {
		JsonNode figures = RunFigures.of(withInput(PINNED, "another"), RunFigures.kept(REPORT),
				MissingNode.getInstance());

		assertThat(figures.get("inputDigest").stringValue()).isEqualTo("another");
	}

	@Test
	void of_aRunWhoseResultDigestIsNotItsResults_keepsTheDigestAsItIs() {
		JsonNode figures = RunFigures.of(REPORT, RunFigures.kept(REPORT), MissingNode.getInstance());

		assertThat(figures.get("resultDigest").stringValue()).isEqualTo("out");
	}

	@Test
	void of_aDatabaseOfSeveralInstances_readsItsCpuCeilingAndItsFormulaAsTheFrozenEngines() {
		GoldenRun run = GoldenRun.of("mongodb-hot-shard", "base");

		JsonNode figures = RunFigures.of(run.java(), run.python(), run.frozen());

		assertThat(JsonDiff.differences(run.python(), figures)).isEmpty();
	}

	@Test
	void of_aRunWithMessagesAndAnApiTheFrozenEnginePricedAtZero_isTheFrozenEnginesFigures() {
		GoldenRun run = GoldenRun.of("kafka-order-pipeline", "base");

		JsonNode figures = RunFigures.of(run.java(), run.python(), run.frozen());

		assertThat(JsonDiff.differences(run.python(), figures)).isEmpty();
	}

	@Test
	void of_anApiTheFrozenEnginePricedAtZero_keepsBothDigestsOverThatPrice() {
		GoldenRun run = GoldenRun.of("auth-token-introspection", "base");

		JsonNode figures = RunFigures.of(run.java(), run.python(), run.frozen());

		assertThat(figures.get("inputDigest")).isEqualTo(run.python().get("inputDigest"));
		assertThat(figures.get("resultDigest")).isEqualTo(run.python().get("resultDigest"));
	}

	@Test
	void of_aCacheThatRefusedLookups_departsInItsResultDigestAlone() {
		GoldenRun run = GoldenRun.of("cdn-video-streaming", "shield-down");

		JsonNode figures = RunFigures.of(run.java(), run.python(), run.frozen());

		assertThat(JsonDiff.differences(run.python(), figures)).containsExactly("/resultDigest");
	}

	// the report, run on the input given, with the input digest given
	private static JsonNode withInput(JsonNode input, String inputDigest) {
		ObjectNode report = (ObjectNode) REPORT.deepCopy();
		report.set("input", input);
		((ObjectNode) report.get("provenance")).put("inputDigest", inputDigest);
		return report;
	}

}
