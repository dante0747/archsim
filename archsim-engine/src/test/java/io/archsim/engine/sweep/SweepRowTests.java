package io.archsim.engine.sweep;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.report.RunReport;
import io.archsim.model.document.Objectives;

import static org.assertj.core.api.Assertions.assertThat;

/** A load sweep's step: what its run measured, and whether it meets the objectives. */
class SweepRowTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Objectives OBJECTIVES = new Objectives(300.0, 500.0, 0.999, 2000.0, null);

	@Test
	void of_aStepsReport_readsItsFiguresAndTheFirstBottlenecksPart() {
		RunReport report = RunReport.read(JSON.readTree("""
				{"summary": {"latencyMs": {"p50": 40.0, "p95": 90.0, "p99": 120.0}, "offeredRps": 401.5,
				             "throughputRps": 398.5, "successRate": 0.99, "failureBreakdown": []},
				 "findings": [{"kind": "hot_key", "nodeId": "api"}, {"kind": "bottleneck", "nodeId": "db"},
				              {"kind": "bottleneck", "nodeId": "api"}],
				 "nodes": [{"id": "api", "type": "service", "category": "compute", "label": "API", "summary": {}},
				         {"id": "db", "type": "database", "category": "storage",
				            "label": "Orders DB", "summary": {}}],
				 "provenance": {"resultDigest": "abc"},
				 "timeseries": {"bucketS": 1.0},
				 "window": {"warmupS": 2.0, "durationS": 12.0}}"""));

		SweepRow row = SweepRow.of(400.0, report);

		assertThat(row).isEqualTo(new SweepRow(400.0, 401.5, 398.5, 40.0, 90.0, 120.0, null, 0.99, "Orders DB", "abc"));
	}

	@Test
	void meets_aStepNotCompleted_cannotSay() {
		assertThat(row(null, 90.0, 0.999).meets(OBJECTIVES)).isNull();
	}

	@Test
	void meets_figuresOnTheirTargets_meetThem() {
		assertThat(row(100.0, 300.0, 0.999).meets(OBJECTIVES)).isTrue();
	}

	@Test
	void meets_aStepBelowTheThroughputTarget_isNotJudgedOnIt() {
		assertThat(row(100.0, 90.0, 1.0).meets(OBJECTIVES)).isTrue();
	}

	@Test
	void meets_aStepWithoutAP95_missesAP95Target() {
		assertThat(row(100.0, null, 1.0).meets(OBJECTIVES)).isFalse();
	}

	private static SweepRow row(@Nullable Double throughput, @Nullable Double p95, double successRate) {
		return new SweepRow(100.0, 100.0, throughput, null, p95, 120.0, p95, successRate, null, "abc");
	}

}
