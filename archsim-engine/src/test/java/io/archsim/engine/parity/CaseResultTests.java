package io.archsim.engine.parity;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A template case's Java runs against Python's: run by run, and by the bar.
 */
class CaseResultTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void of_javaFiguresEqualToPythonsButTheSeed_isAnExactReplay() {
		RecordedCase recorded = recorded(python(42, 10, 20, "a"), python(43, 12, 22, "b"));

		CaseResult result = CaseResult.of(recorded, List.of(java(10, 20, "a"), java(12, 22, "b")));

		assertThat(result).extracting(CaseResult::runs, CaseResult::exact, CaseResult::digestsEqual)
			.containsExactly(2, 2, 2);
		assertThat(result.replaysExactly()).isTrue();
	}

	@Test
	void of_aRunWithOtherFiguresAndPythonsDigest_isNotAnExactReplay() {
		RecordedCase recorded = recorded(python(42, 10, 20, "a"), python(43, 12, 22, "b"));

		CaseResult result = CaseResult.of(recorded, List.of(java(10, 20, "a"), java(12, 23, "b")));

		assertThat(result).extracting(CaseResult::exact, CaseResult::digestsEqual).containsExactly(1, 2);
		assertThat(result.replaysExactly()).isFalse();
	}

	@Test
	void of_aJavaMedianInsideTheRangeButMoreThanTwoPercentOff_missesTheBarOnThatTestAlone() {
		RecordedCase recorded = recorded(python(42, 10, 20, "a"), python(43, 10, 30, "b"));

		CaseResult result = CaseResult.of(recorded, List.of(java(10, 21, "c"), java(10, 23, "d")));

		assertThat(result.insideRange()).isTrue();
		assertThat(result.withinTwoPercent()).isFalse();
		assertThat(result.meetsTheBar()).isFalse();
		assertThat(result.missed()).containsExactly("p95");
	}

	@Test
	void of_theSameFigures_meetsTheBarWithNothingMissed() {
		RecordedCase recorded = recorded(python(42, 10, 20, "a"), python(43, 12, 22, "b"));

		CaseResult result = CaseResult.of(recorded, List.of(java(10, 20, "a"), java(12, 22, "b")));

		assertThat(result.meetsTheBar()).isTrue();
		assertThat(result.missed()).isEmpty();
	}

	@Test
	void of_aJavaRunFailingWherePythonsRan_missesTheBar() {
		RecordedCase recorded = recorded(python(42, 10, 20, "a"), python(43, 10, 20, "a"), python(44, 10, 20, "a"));

		CaseResult result = CaseResult.of(recorded, List.of(java(10, 20, "a"), java(10, 20, "a"), failed()));

		assertThat(result.missed()).containsExactly("1 seed failed otherwise than Python's (Java failed 1, Python 0)");
		assertThat(result.meetsTheBar()).isFalse();
	}

	@Test
	void of_aCaseWhosePythonRunsAllFailed_missesTheBarWhenJavaRan() {
		RecordedCase recorded = recorded(failed(42), failed(43));

		CaseResult result = CaseResult.of(recorded, List.of(java(10, 20, "a"), java(12, 22, "b")));

		assertThat(result.missed()).containsExactly("2 seeds failed otherwise than Python's (Java failed 0, Python 2)");
	}

	@Test
	void of_runsFailingAsPythonsDid_replayExactlyWithoutADigest() {
		RecordedCase recorded = recorded(failed(42), failed(43));

		CaseResult result = CaseResult.of(recorded, List.of(failed(), failed()));

		assertThat(result).extracting(CaseResult::exact, CaseResult::digestsEqual).containsExactly(2, 0);
		assertThat(result.meetsTheBar()).isTrue();
	}

	@Test
	void of_runsFailingOtherwiseThanPythons_missesTheBar() {
		RecordedCase recorded = recorded(failed(42), failed(43));

		CaseResult result = CaseResult.of(recorded,
				List.of(failed(), JSON.createObjectNode().put("error", "NullPointerException: no part")));

		assertThat(result.missed()).containsExactly("1 seed failed otherwise than Python's (Java failed 2, Python 2)");
	}

	private static RecordedCase recorded(JsonNode... runs) {
		return new RecordedCase("queueing-lab", "base", List.of(runs));
	}

	private static JsonNode python(long seed, double p50, double p95, String digest) {
		return ((ObjectNode) java(p50, p95, digest)).put("seed", seed);
	}

	private static JsonNode failed(long seed) {
		return failed().put("seed", seed);
	}

	private static ObjectNode failed() {
		return JSON.createObjectNode().put("error", "SimulationLimitError: the run went over 2000000 requests");
	}

	private static JsonNode java(double p50, double p95, String digest) {
		return JSON.readTree("""
				{"resultDigest": "%s", "summary": {"latencyMs": {"p50": %s, "p95": %s}, "throughputRps": 100.0},
				 "nodes": {"api": {"utilization": 0.5}}}""".formatted(digest, p50, p95));
	}

}
