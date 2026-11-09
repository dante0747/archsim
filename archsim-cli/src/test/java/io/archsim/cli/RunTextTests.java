package io.archsim.cli;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a report no run writes makes {@code archsim run} print: lines Python skipped when
 * their part was empty or missing, which the recorded runs always have.
 */
class RunTextTests {

	private static final JsonNode BARE = JsonMapper.builder().build().readTree("""
			{"summary": {"offeredRps": 10, "throughputRps": 10, "successRate": 1, "latencyMs": {"p50": 1, "p95": 2,
			 "p99": 3}}, "simulationMode": {}, "confidence": {}, "findings": [],
			 "provenance": {"seed": 7, "inputDigest": "abc", "resultDigest": "def"}}""");

	@Test
	void lines_aReportWithAnEmptyModeConfidenceAndNoEstimate_isTheSummaryAndTheDigests() {
		assertThat(RunText.lines(BARE)).containsExactly(
				"offered 10.0 req/s | throughput 10.0 req/s | success 100.00% | p50 1.0 ms | p95 2.0 ms | p99 3.0 ms",
				"  seed=7 inputDigest=abc resultDigest=def");
	}

}
