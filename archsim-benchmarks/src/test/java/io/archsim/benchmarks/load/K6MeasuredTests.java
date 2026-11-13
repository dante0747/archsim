package io.archsim.benchmarks.load;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.comparison.StepAggregation;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The window k6 measured, from its end-of-run summary: the latency of the requests that
 * succeeded, how many were sent, dropped, failed and succeeded, and the throughput.
 */
class K6MeasuredTests {

	@Test
	void of_aSummary_isItsMeasuredWindow() {
		ObjectNode measured = K6Measured.of(summary(990, 995, 5, 5), 20);

		assertThat(measured).isEqualTo(JsonText.read("""
				{"p50": 1.2, "p95": 3.4, "p99": 5.6, "mean": 1.5, "sent": 995, "dropped": 5, "failed": 5,
				 "succeeded": 990, "successRate": 0.99, "throughputRps": 49.5}"""));
	}

	// k6 reports its latency trend as zeros when nothing succeeded (run 4, hot-row, 1,126
	// req/s)
	@Test
	void of_noRequestSucceeded_hasNoLatency() {
		ObjectNode measured = K6Measured.of(summary(0, 18409, 18409, 4114), 20);

		assertThat(measured).isEqualTo(JsonText.read("""
				{"p50": null, "p95": null, "p99": null, "mean": null, "sent": 18409, "dropped": 4114,
				 "failed": 18409, "succeeded": 0, "successRate": 0.0, "throughputRps": 0.0}"""));
	}

	@Test
	void of_noRequestSucceeded_isAFailedRepeat() {
		ObjectNode measured = K6Measured.of(summary(0, 18409, 18409, 4114), 20);

		assertThat(StepAggregation.failedRepeat(measured)).isTrue();
	}

	@Test
	void of_nothingSent_hasNoSuccessRate() {
		ObjectNode measured = K6Measured.of(JsonText.object("{}"), 20);

		assertThat(measured).isEqualTo(JsonText.read("""
				{"p50": null, "p95": null, "p99": null, "mean": null, "sent": 0, "dropped": 0, "failed": 0,
				 "succeeded": 0, "successRate": null, "throughputRps": 0.0}"""));
	}

	// the load generator failed, not the system
	@Test
	void of_nothingSent_isNotAFailedRepeat() {
		ObjectNode measured = K6Measured.of(JsonText.object("{}"), 20);

		assertThat(StepAggregation.failedRepeat(measured)).isFalse();
	}

	// a k6 end-of-run summary (data.metrics) for the measured scenario
	static JsonNode summary(int count, int sent, int failed, int dropped) {
		String trend = (count > 0)
				? "\"avg\": 1.5, \"min\": 0.2, \"med\": 1.2, \"p(95)\": 3.4, \"p(99)\": 5.6, \"max\": 9.0"
				: "\"avg\": 0, \"min\": 0, \"med\": 0, \"p(95)\": 0, \"p(99)\": 0, \"max\": 0";
		return JsonText.read("{\"http_req_duration{scenario:measure,expected_response:true}\": {\"values\": {" + trend
				+ ", \"count\": " + count + "}}, \"http_reqs{scenario:measure}\": {\"values\": {\"count\": " + sent
				+ "}}, \"http_req_failed{scenario:measure}\": {\"values\": {\"passes\": " + failed + ", \"fails\": "
				+ (sent - failed) + "}}, \"dropped_iterations{scenario:measure}\": {\"values\": {\"count\": " + dropped
				+ "}}}");
	}

}
