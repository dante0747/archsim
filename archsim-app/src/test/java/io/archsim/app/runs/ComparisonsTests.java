package io.archsim.app.runs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comparing two finished runs, from their stored reports.
 */
class ComparisonsTests extends RunTestSupport {

	@Test
	void compare_twoSucceededRuns_comparesTheirReportsNamingThem() {
		prepares(42, null, true);
		String project = project("team", "editor");
		String first = started(start(project, "{}")).path("id").asString();
		String second = started(start(project, "{}")).path("id").asString();
		finish(first, 100.0, report(100.0, 1));
		finish(second, 150.0, report(150.0, 3));

		JsonNode answer = json(this.mvc().get().uri("/api/v1/compare?a=" + first + "&b=" + second).exchange());

		assertThat(answer.path("labels").path("a").asString()).isEqualTo("Current design");
		assertThat(answer.path("inputDiff").get(0).path("path").asString()).isEqualTo("node[api].replicas");
		JsonNode p95 = answer.path("kpis").get(6);
		assertThat(p95.path("key").asString()).isEqualTo("latencyMs.p95");
		assertThat(p95.path("verdict").asString()).isEqualTo("worse");
		assertThat(answer.path("explanations").get(0).asString())
			.isEqualTo("Current design differs from Current design in: API: replicas 1 \u2192 3.");
		assertThat(answer.path("runs").path("b").asString()).isEqualTo(second);
	}

	@Test
	void compare_aReportWithoutThePartsEveryReportHas_failsAsTheServersError() {
		prepares(42, null, true);
		String project = project("team", "editor");
		String first = started(start(project, "{}")).path("id").asString();
		String second = started(start(project, "{}")).path("id").asString();
		finish(first, 100.0);
		finish(second, 150.0, report(150.0, 3));

		assertThat(this.mvc().get().uri("/api/v1/compare?a=" + first + "&b=" + second)).hasStatus(500);
	}

	@Test
	void compare_unfinishedRun_isAConflict() {
		prepares(42, null, true);
		String project = project("team", "editor");
		String first = started(start(project, "{}")).path("id").asString();
		String second = started(start(project, "{}")).path("id").asString();

		assertThat(this.mvc().get().uri("/api/v1/compare?a=" + first + "&b=" + second)).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Run " + first.substring(0, 8) + " is queued; cannot compare");
	}

	@Test
	void compare_withoutRuns_isRefusedAsFastApiRefusesIt() {
		assertThat(this.mvc().get().uri("/api/v1/compare")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail[*].loc[1]")
			.asArray()
			.containsExactly("a", "b");
	}

	/**
	 * A run's report with what a comparison reads.
	 * @param p95 its p95 latency
	 * @param replicas the API's replicas in the design it simulated
	 * @return the report's JSON
	 */
	private static String report(double p95, int replicas) {
		return """
				{"summary": {"latencyMs": {"mean": 50.0, "p50": 40.0, "p95": %s, "p99": 200.0},
				             "throughputRps": 398.0, "successRate": 1.0, "failureBreakdown": []},
				 "nodes": [{"id": "api", "type": "service", "category": "compute", "label": "API", "status": "ok",
				            "summary": {"utilization": 0.5}}],
				 "findings": [], "provenance": {"resultDigest": "d", "seed": 42, "engineVersion": "0.16.0"},
				 "timeseries": {"bucketS": 1.0, "t": [0.0]},
				 "window": {"warmupS": 0.0, "durationS": 1.0, "measuredS": 1.0},
				 "input": {"architecture": {"nodes": [{"id": "api", "type": "service", "label": "API",
				                                       "config": {"replicas": %d}}], "edges": []},
				           "workload": {"rps": 400}}}""".formatted(p95, replicas);
	}

}
