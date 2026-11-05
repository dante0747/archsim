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
			             "offeredRps": 400, "throughputRps": 398.5, "successRate": 1.0},
			 "findings": [{"kind": "bottleneck", "nodeId": "api", "evidence": {"share": 0.5}},
			              {"title": "no kind"}],
			 "nodes": [{"id": "api", "label": "API", "summary": {"arrivals": 1234, "slots": 16},
			            "timeseries": {"queueLength": [1, null, 2], "replicas": null}},
			           {"id": "db", "label": "DB", "summary": {}}],
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
	void read_aValueTheReportLeavesOutOrWritesAsNull_isNull() {
		RunReport report = RunReport.read(json(REPORT));

		assertThat(report.summary().latencyMs().p99()).isNull();
		assertThat(report.summary().latencyWithFailuresMs()).isNull();
		assertThat(report.findings().get(1).kind()).isNull();
		assertThat(report.nodes().get(1).summary().slots()).isNull();
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

	private static JsonNode json(String text) {
		return JSON.readTree(text);
	}

}
