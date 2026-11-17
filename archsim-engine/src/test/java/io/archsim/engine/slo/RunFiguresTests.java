package io.archsim.engine.slo;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run's figures from its summary in either shape: a report's, or a run's compact one as
 * the workers store it for listings.
 */
class RunFiguresTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void read_aReportsSummary_readsItsLatenciesUnderLatencyMs() {
		RunFigures figures = RunFigures.read(JSON.readTree("""
				{"latencyMs": {"p50": 40.0, "p95": 90.0, "p99": 120.0, "max": 300.0},
				 "successRate": 1.0, "throughputRps": 398.5, "p95": 1.0, "failureBreakdown": []}"""));

		assertThat(figures).isEqualTo(new RunFigures(40.0, 90.0, 120.0, 1.0, 398.5, 1.0, null, false));
	}

	@Test
	void read_aRunsCompactSummary_readsItsFlatLatencies() {
		RunFigures figures = RunFigures.read(JSON.readTree("""
				{"offeredRps": 400.0, "throughputRps": 398.5, "successRate": 0.99, "p50": 40.0, "p95": 90.0,
				 "p99": null, "bottleneck": "API"}"""));

		assertThat(figures).isEqualTo(new RunFigures(40.0, 90.0, null, 0.99, 398.5, 0.99, null, false));
	}

	@Test
	void read_aReportsSummaryWithQueues_readsItsAvailabilityAndMessageAge() {
		RunFigures figures = RunFigures.read(JSON.readTree("""
				{"latencyMs": {"p50": 40.0, "p95": 90.0, "p99": 120.0, "max": 300.0},
				 "successRate": 1.0, "throughputRps": 398.5, "failureBreakdown": [],
				 "lostMessages": 46, "availability": 0.99, "messageAgeP95Ms": 21040.0}"""));

		assertThat(figures).isEqualTo(new RunFigures(40.0, 90.0, 120.0, 1.0, 398.5, 0.99, 21040.0, true));
	}

	@Test
	void read_aRunsCompactSummaryWithQueues_readsItsAvailabilityAndMessageAge() {
		RunFigures figures = RunFigures.read(JSON.readTree("""
				{"throughputRps": 398.5, "successRate": 1.0, "p50": 40.0, "p95": 90.0, "p99": null,
				 "lostMessages": 46, "availability": 0.99, "messageAgeP95Ms": 21040.0}"""));

		assertThat(figures).isEqualTo(new RunFigures(40.0, 90.0, null, 1.0, 398.5, 0.99, 21040.0, true));
	}

}
