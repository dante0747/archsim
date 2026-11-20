package io.archsim.engine.parity;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

	private static final String REFUSED = "refusedLookups";

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

		CaseResult result = CaseResult.of(recorded, List.of(java(10, 20, "a"), java(10, 20, "a"), javaFailed()));

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

		CaseResult result = CaseResult.of(recorded, List.of(javaFailed(), javaFailed()));

		assertThat(result).extracting(CaseResult::exact, CaseResult::digestsEqual).containsExactly(2, 0);
		assertThat(result.meetsTheBar()).isTrue();
	}

	@Test
	void of_runsFailingOtherwiseThanPythons_missesTheBar() {
		RecordedCase recorded = recorded(failed(42), failed(43));

		CaseResult result = CaseResult.of(recorded, List.of(javaFailed(),
				new JavaRun(JSON.createObjectNode().put("error", "NullPointerException: no part"), Set.of())));

		assertThat(result.missed()).containsExactly("1 seed failed otherwise than Python's (Java failed 2, Python 2)");
	}

	@Test
	void of_aRunDepartingInItsResultDigestAloneWhereTheBaselinesKeepTooLittle_isCountedUnderThatDeparture() {
		RecordedCase recorded = recorded(python(42, 10, 20, "a"), python(43, 12, 22, "b"));

		CaseResult result = CaseResult.of(recorded, List.of(java(10, 20, "a"), departing(12, 22, "c")));

		assertThat(result.departing()).isEqualTo(new CaseResult.Departing(1, Map.of(REFUSED, 1)));
	}

	@Test
	void of_aRunShowingSuchADepartureThatDepartsInAnotherFigureToo_isNotCountedAsDepartingThereAlone() {
		RecordedCase recorded = recorded(python(42, 10, 20, "a"));

		CaseResult result = CaseResult.of(recorded, List.of(departing(10, 21, "c")));

		assertThat(result.departing()).isEqualTo(new CaseResult.Departing(0, Map.of()));
	}

	@Test
	void of_aRunShowingSuchADepartureThatReplaysExactly_isAnExactReplayNotADeparture() {
		RecordedCase recorded = recorded(python(42, 10, 20, "a"));

		CaseResult result = CaseResult.of(recorded, List.of(departing(10, 20, "a")));

		assertThat(result.exact()).isEqualTo(1);
		assertThat(result.departing()).isEqualTo(new CaseResult.Departing(0, Map.of()));
	}

	private static RecordedCase recorded(JsonNode... runs) {
		return new RecordedCase("queueing-lab", "base", List.of(runs));
	}

	private static JsonNode python(long seed, double p50, double p95, String digest) {
		return ((ObjectNode) figures(p50, p95, digest)).put("seed", seed);
	}

	private static JsonNode failed(long seed) {
		return error().put("seed", seed);
	}

	private static JavaRun javaFailed() {
		return new JavaRun(error(), Set.of());
	}

	private static ObjectNode error() {
		return JSON.createObjectNode().put("error", "SimulationLimitError: the run went over 2000000 requests");
	}

	private static JavaRun java(double p50, double p95, String digest) {
		return new JavaRun(figures(p50, p95, digest), Set.of());
	}

	// a run showing a departure the baselines keep too little of to read
	private static JavaRun departing(double p50, double p95, String digest) {
		return new JavaRun(figures(p50, p95, digest), Set.of(REFUSED));
	}

	private static JsonNode figures(double p50, double p95, String digest) {
		return JSON.readTree("""
				{"resultDigest": "%s", "summary": {"latencyMs": {"p50": %s, "p95": %s}, "throughputRps": 100.0},
				 "nodes": {"api": {"utilization": 0.5}}}""".formatted(digest, p50, p95));
	}

}
