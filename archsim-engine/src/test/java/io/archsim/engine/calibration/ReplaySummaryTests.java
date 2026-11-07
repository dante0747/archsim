package io.archsim.engine.calibration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The replay summary's line, in the Python engine's words, for the records the golden
 * files do not hold.
 */
class ReplaySummaryTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void of_endToEndP95_givesItsErrorAndTheWorstOfAllChecks() {
		assertThat(ReplaySummary.of(record("p95"))).hasValue("This model reproduces the observed p95 (the observed "
				+ "range) within 4% (over); the largest of 2 replay errors is -30% (db CPU).");
	}

	@Test
	void of_noEndToEndP95_givesTheWorstOfAllChecks() {
		assertThat(ReplaySummary.of(record("p99")))
			.hasValue("Replay of the observed range: the largest of 2 replay errors is -30% (db CPU).");
	}

	@Test
	void of_noRecord_isEmpty() {
		assertThat(ReplaySummary.of(null)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"replay\": []}", "{\"replay\": [{\"error\": null}]}" })
	void of_noCheckThatMeasuredAnError_isEmpty(String record) {
		assertThat(ReplaySummary.of(JSON.readTree(record))).isEmpty();
	}

	/**
	 * A record of three checks, one without an error, its end-to-end check of the metric
	 * given; an empty time range.
	 */
	private static ObjectNode record(String endToEndMetric) {
		return (ObjectNode) JSON.readTree("""
				{"timeRange": "", "replay": [
				  {"scope": "component", "metric": "cpuCores", "label": "db", "error": -0.3},
				  {"scope": "component", "metric": "p50", "label": "api", "error": null},
				  {"scope": "end_to_end", "metric": "%s", "label": "End to end", "error": 0.044}]}"""
			.formatted(endToEndMetric));
	}

}
