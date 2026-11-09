package io.archsim.engine.parity;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The port plan's bar: the Java median inside Python's range and within 2% of its median.
 */
class ParityBarTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void median_anEvenCount_isTheMeanOfTheMiddleTwo() {
		assertThat(ParityBar.median(List.of(4.0, 1.0, 3.0, 2.0))).isEqualTo(2.5);
	}

	@Test
	void median_anOddCount_isTheMiddleOne() {
		assertThat(ParityBar.median(List.of(5.0, 1.0, 3.0))).isEqualTo(3.0);
	}

	@Test
	void withinTwoPercent_aMedianTwoPercentOff_isTrue() {
		assertThat(ParityBar.withinTwoPercent(100, 102)).isTrue();
	}

	@Test
	void withinTwoPercent_aMedianMoreThanTwoPercentOff_isFalse() {
		assertThat(ParityBar.withinTwoPercent(100, 102.1)).isFalse();
	}

	@Test
	void distance_twoZeroMedians_isZero() {
		assertThat(ParityBar.distance(0, 0)).isZero();
	}

	@Test
	void distance_aZeroPythonMedianAlone_isInfinite() {
		assertThat(ParityBar.distance(0, 1)).isInfinite();
	}

	@Test
	void distance_aJavaMedianOff_isTheGapAsAShareOfPythons() {
		assertThat(ParityBar.distance(200, 205)).isEqualTo(0.025);
	}

	@Test
	void judge_runsWithAPartsUtilization_judgesTheLatenciesThroughputAndIt() {
		List<JsonNode> runs = List.of(run(10, 20, 100, 0.5), run(12, 22, 98, 0.6));

		List<MetricVerdict> verdicts = ParityBar.judge(runs, runs);

		assertThat(verdicts).extracting(MetricVerdict::metric)
			.containsExactly("p50", "p95", "throughput", "utilization api");
	}

	@Test
	void judge_theSameRuns_meetsTheBar() {
		List<JsonNode> runs = List.of(run(10, 20, 100, 0.5), run(12, 22, 98, 0.6));

		assertThat(ParityBar.judge(runs, runs)).allMatch(MetricVerdict::holds);
	}

	@Test
	void judge_aMetricNoJavaRunMeasured_missesTheBar() {
		List<JsonNode> python = List.of(run(10, 20, 100, 0.5));
		List<JsonNode> java = List.of(JSON.readTree("""
				{"summary": {"latencyMs": {"p50": 10, "p95": 20}, "throughputRps": 100}, "nodes": {}}"""));

		assertThat(ParityBar.judge(python, java)).filteredOn((verdict) -> !verdict.holds())
			.extracting(MetricVerdict::metric)
			.containsExactly("utilization api");
	}

	private static JsonNode run(double p50, double p95, double throughput, double utilization) {
		return JSON.readTree("""
				{"summary": {"latencyMs": {"p50": %s, "p95": %s}, "throughputRps": %s},
				 "nodes": {"clients": {"arrivals": 10}, "api": {"utilization": %s}}}""".formatted(p50, p95, throughput,
				utilization));
	}

}
