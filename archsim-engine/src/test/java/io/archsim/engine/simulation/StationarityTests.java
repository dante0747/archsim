package io.archsim.engine.simulation;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.report.RunReport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Whether a sampled run reached a steady state: its measured window's halves agree, it
 * keeps up with its offered load, and nothing is still growing.
 */
class StationarityTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String FLAT = "[400, 400, 400, 400, 400, 400, 400, 400, 400, 400, 400, 400]";

	@Test
	void judge_halvesThatAgree_reachedASteadyState() {
		SteadyState state = Stationarity.judge(report(1.0, 12.0, "[3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3]"));

		assertThat(state).isEqualTo(new SteadyState.Judged(0.0, 1.0, 0.0, List.of()));
		assertThat(state.reached()).isTrue();
	}

	@Test
	void judge_aQueueStillGrowing_didNotReachOne() {
		SteadyState state = Stationarity.judge(report(1.0, 12.0, "[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]"));

		assertThat(state).isInstanceOfSatisfying(SteadyState.Judged.class,
				(judged) -> assertThat(judged.growing()).containsExactly("api.queueLength"));
		assertThat(state.reached()).isFalse();
	}

	@Test
	void judge_fewerThanFourMeasuredBuckets_cannotJudge() {
		SteadyState state = Stationarity.judge(report(1.0, 4.9, "[]"));

		assertThat(state).isEqualTo(new SteadyState.Unjudged("too few time buckets to judge"));
		assertThat(state.reached()).isFalse();
	}

	@Test
	void judge_bucketsWithoutALength_failAsThePythonEngineDivides() {
		RunReport report = report(0.0, 12.0, "[]");

		assertThatExceptionOfType(ArithmeticException.class).isThrownBy(() -> Stationarity.judge(report))
			.withMessage("float division by zero");
	}

	private static RunReport report(double bucketS, double durationS, String queue) {
		return RunReport.read(JSON.readTree("""
				{"summary": {"latencyMs": {}}, "findings": [], "provenance": {"resultDigest": "abc"},
				 "nodes": [{"id": "api", "label": "API", "summary": {}, "timeseries": {"queueLength": %s}}],
				 "timeseries": {"bucketS": %s, "throughputRps": %s, "offeredRps": %s, "latencyP95Ms": %s},
				 "window": {"warmupS": 2.0, "durationS": %s}}""".formatted(queue, bucketS, FLAT, FLAT, FLAT,
				durationS)));
	}

}
