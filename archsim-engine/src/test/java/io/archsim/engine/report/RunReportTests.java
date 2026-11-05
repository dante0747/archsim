package io.archsim.engine.report;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Reading a run's report into the engine's records of the parts it reads (ADR-0039): what
 * the records name is read, the rest is skipped, and what the report leaves out is null.
 */
class RunReportTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String REPORT = """
			{"reportVersion": "1.14",
			 "summary": {"latencyMs": {"count": 10, "mean": 50.0, "p50": 40.0, "p90": 80, "p95": 90.5, "p99": null},
			             "offeredRps": 400, "throughputRps": 398.5, "successRate": 1.0, "failureBreakdown": []},
			 "findings": [{"kind": "bottleneck", "nodeId": "api", "evidence": {"share": 0.5}},
			              {"title": "no kind"}],
			 "nodes": [{"id": "api", "type": "service", "category": "compute", "label": "API",
			            "summary": {"arrivals": 1234, "slots": 16, "counters": {"misses": 7},
			                        "rejected": {"queue_full": 5}},
			            "timeseries": {"queueLength": [1, null, 2], "replicas": null},
			            "behaviour": [{"id": "primary_reads", "label": "Reads go to the primary"}]},
			           {"id": "db", "type": "database", "category": "storage", "label": "DB", "summary": {}}],
			 "edges": [{"id": "api-db", "source": "api", "target": "db", "callRps": 120.5, "summary": {}},
			           {"id": "api-cache"}],
			 "explain": {"system": [{"id": "throughput"}],
			             "latencyBreakdown": [{"label": "API", "nodeId": "api", "meanMs": 8.0, "share": 0.6},
			                                  {"label": "Network", "nodeId": null, "meanMs": 5.0, "share": null}]},
			 "provenance": {"resultDigest": "abc", "seed": 42},
			 "timeseries": {"bucketS": 0.5, "t": [0, 0.5], "throughputRps": [1.0, null],
			                "offeredRps": null, "latencyP95Ms": [3.5]},
			 "window": {"warmupS": 2.0, "durationS": 12.0, "measuredS": 10.0},
			 "traces": [{"id": 1}]}""";

	@Test
	void read_aReport_readsWhatTheRecordsName() {
		RunReport report = RunReport.read(json(REPORT));

		assertThat(report.summary().latencyMs().p95()).isEqualTo(90.5);
		assertThat(report.summary().throughputRps()).isEqualTo(398.5);
		assertThat(report.nodes()).extracting(ReportNode::label).containsExactly("API", "DB");
		assertThat(report.nodes().getFirst().summary().arrivals()).isEqualTo(1234L);
		assertThat(report.provenance().resultDigest()).isEqualTo("abc");
		assertThat(report.timeseries().bucketS()).isEqualTo(0.5);
		assertThat(report.window().durationS()).isEqualTo(12.0);
	}

	@Test
	void read_aReport_readsItsLinksAndWhereARequestsTimeGoes() {
		RunReport report = RunReport.read(json(REPORT));

		assertThat(report.edges()).extracting(ReportEdge::id).containsExactly("api-db", "api-cache");
		assertThat(report.edges().getFirst().callRps()).isEqualTo(120.5);
		assertThat(report.latencyBreakdown()).extracting(LatencyShare::label).containsExactly("API", "Network");
		assertThat(report.latencyBreakdown().getFirst().nodeId()).isEqualTo("api");
		assertThat(report.latencyBreakdown().getFirst().share()).isEqualTo(0.6);
	}

	@Test
	void read_aReportWithoutLinksOrABreakdown_hasNone() {
		RunReport report = RunReport.read(json(REPORT.replace("\"edges\"", "\"edgesGone\"")
			.replace("\"latencyBreakdown\"", "\"latencyBreakdownGone\"")));

		assertThat(report.edges()).isEmpty();
		assertThat(report.latencyBreakdown()).isEmpty();
	}

	@Test
	void read_aReportWithoutAnExplanation_hasNoBreakdown() {
		RunReport report = RunReport.read(json(REPORT.replace("\"explain\"", "\"explainGone\"")));

		assertThat(report.latencyBreakdown()).isEmpty();
	}

	@Test
	void read_aLinkWithoutItsId_isRefused() {
		JsonNode report = json(REPORT.replace("{\"id\": \"api-cache\"}", "{\"callRps\": 3.0}"));

		assertThatExceptionOfType(JacksonException.class).isThrownBy(() -> RunReport.read(report))
			.withMessageContaining("a report's link has its id");
	}

	@Test
	void read_aValueTheReportLeavesOutOrWritesAsNull_isNull() {
		RunReport report = RunReport.read(json(REPORT));

		assertThat(report.summary().latencyMs().p99()).isNull();
		assertThat(report.summary().latencyWithFailuresMs()).isNull();
		assertThat(report.findings().get(1).kind()).isNull();
		assertThat(report.nodes().get(1).summary().slots()).isNull();
		assertThat(report.edges().get(1).callRps()).isNull();
		assertThat(report.latencyBreakdown().get(1).nodeId()).isNull();
		assertThat(report.latencyBreakdown().get(1).share()).isNull();
	}

	@Test
	void read_seriesTheReportWritesAsNull_areEmpty() {
		RunReport report = RunReport.read(json(REPORT));

		assertThat(report.timeseries().offeredRps()).isEmpty();
		assertThat(report.timeseries().throughputRps()).containsExactly(1.0, null);
		assertThat(report.nodes().getFirst().timeseries()).containsOnlyKeys("queueLength");
		assertThat(report.nodes().get(1).timeseries()).isEmpty();
	}

	@Test
	void read_aReportWithoutItsWindow_isRefused() {
		JsonNode report = json(REPORT.replace("\"window\"", "\"windowGone\""));

		assertThatExceptionOfType(JacksonException.class).isThrownBy(() -> RunReport.read(report))
			.withMessageContaining("a report has its window");
	}

	@Test
	void read_aPartWithoutItsType_isRefused() {
		JsonNode report = json(REPORT.replace("\"type\": \"service\", ", ""));

		assertThatExceptionOfType(JacksonException.class).isThrownBy(() -> RunReport.read(report))
			.withMessageContaining("a report's part has its type");
	}

	@Test
	void read_aPartWithoutItsCategory_isRefused() {
		JsonNode report = json(REPORT.replace("\"category\": \"compute\", ", ""));

		assertThatExceptionOfType(JacksonException.class).isThrownBy(() -> RunReport.read(report))
			.withMessageContaining("a report's part has its category");
	}

	@Test
	void read_aSummaryWithoutItsFailureBreakdown_isRefused() {
		JsonNode report = json(REPORT.replace(", \"failureBreakdown\": []", ""));

		assertThatExceptionOfType(JacksonException.class).isThrownBy(() -> RunReport.read(report))
			.withMessageContaining("a report's summary has its failures");
	}

	@Test
	void counter_aCountThePartKeeps_isItsCount() {
		assertThat(api().summary().counter("misses")).isEqualTo(7);
	}

	@Test
	void counter_aCountThePartDoesNotKeep_isZero() {
		assertThat(api().summary().counter("wasted")).isZero();
	}

	@Test
	void rejectedFor_aReasonThePartRejectedFor_isHowMany() {
		assertThat(api().summary().rejectedFor("queue_full")).isEqualTo(5);
	}

	@Test
	void rejectedFor_aReasonThePartDidNotRejectFor_isZero() {
		assertThat(api().summary().rejectedFor("throttled")).isZero();
	}

	@Test
	void behaves_aBehaviourItsTechnologyAdds_isTrue() {
		assertThat(api().behaves("primary_reads")).isTrue();
	}

	@Test
	void behaves_aPartWithoutBehaviours_isFalse() {
		assertThat(RunReport.read(json(REPORT)).nodes().get(1).behaves("primary_reads")).isFalse();
	}

	private static ReportNode api() {
		return RunReport.read(json(REPORT)).nodes().getFirst();
	}

	private static JsonNode json(String text) {
		return JSON.readTree(text);
	}

}
