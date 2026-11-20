package io.archsim.engine.parity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The parity run's report: each test's count of cases, the exact replays, the metric
 * furthest from Python's and the cases that miss.
 */
class ParityReportTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final CaseResult.Failures NONE = new CaseResult.Failures(0, 0, 0);

	private static final CaseResult.Departing NOT_DEPARTING = new CaseResult.Departing(0, Map.of());

	private static final String REFUSED = "refusedLookups";

	@TempDir
	private Path out;

	@Test
	void write_aCaseMeetingTheBarAndOneOnlyInsideTheRange_countsEachTest() throws IOException {
		JsonNode report = written(meetingAndMissing());

		assertThat(List.of("cases", "casesMeetingTheBar", "casesInsideTheRange", "casesWithinTwoPercent",
				"casesWithMismatchedFailures", "casesReplayingExactly", "runs", "exactReplays", "pythonRunsFailed",
				"javaRunsFailed", "digestsEqual"))
			.map((name) -> report.get(name).intValue())
			.containsExactly(2, 1, 2, 1, 0, 1, 4, 3, 0, 0, 1);
	}

	@Test
	void write_aCaseWhoseRunsFailedOtherwiseThanPythons_isCountedWithEachSidesFailures() throws IOException {
		CaseResult failing = new CaseResult("queueing-lab", "spike", 2, 1, 0, NOT_DEPARTING,
				new CaseResult.Failures(1, 2, 1), List.of(verdict("p50", 10.0)));

		JsonNode report = written(List.of(result("base", 2, 2, verdict("p50", 10.0)), failing));

		assertThat(List.of("casesWithMismatchedFailures", "pythonRunsFailed", "javaRunsFailed", "casesMeetingTheBar"))
			.map((name) -> report.get(name).intValue())
			.containsExactly(1, 1, 2, 1);
	}

	@Test
	void write_casesWithRunsDepartingWhereTheBaselinesKeepTooLittle_countsTheRunsAndEachDeparture() throws IOException {
		JsonNode report = written(List.of(departing("base", 1), departing("spike", 2)));

		assertThat(report.get("departingRuns").intValue()).isEqualTo(3);
		assertThat(report.get("departures").toString()).isEqualTo("{\"refusedLookups\":3}");
	}

	@Test
	void write_aCaseThatMisses_isListedWithWhatItMissed() throws IOException {
		JsonNode missing = written(meetingAndMissing()).get("casesMissingTheBar");

		assertThat(missing).hasSize(1);
		assertThat(List.of(missing.get(0).get("scenario").stringValue(), missedOf(missing.get(0))))
			.containsExactly("spike", List.of("p95"));
	}

	@Test
	void write_eachCase_saysWhetherItMeetsTheBarAndWhatItMissed() throws IOException {
		JsonNode cases = written(meetingAndMissing()).get("results");

		assertThat(cases).extracting((one) -> one.get("meetsTheBar").booleanValue(), ParityReportTests::missedOf)
			.containsExactly(tuple(true, List.of()), tuple(false, List.of("p95")));
	}

	@Test
	void write_theCases_namesTheMetricFurthestFromPythons() throws IOException {
		List<CaseResult> results = List.of(result("base", 2, 2, verdict("p50", 10.1)),
				result("spike", 2, 2, verdict("p95", 21.0)));

		JsonNode worst = written(results).get("worst");

		assertThat(List.of(worst.get("scenario").stringValue(), worst.get("verdict").get("metric").stringValue()))
			.containsExactly("spike", "p95");
	}

	@Test
	void write_aMetricNoJavaRunMeasured_isTheWorst() throws IOException {
		MetricVerdict unmeasured = MetricVerdict.of("utilization api", List.of(0.5, 0.6), List.of());
		List<CaseResult> results = List.of(result("base", 2, 2, verdict("p95", 21.0)),
				result("spike", 2, 2, unmeasured));

		JsonNode worst = written(results).get("worst");

		assertThat(worst.get("verdict").get("metric").stringValue()).isEqualTo("utilization api");
	}

	private JsonNode written(List<CaseResult> results) throws IOException {
		ParityReport.write(this.out, results, 2, 1.5);
		return JSON.readTree(this.out.resolve("simulation-parity.json").toFile());
	}

	private static List<String> missedOf(JsonNode one) {
		return one.get("missed").valueStream().map(JsonNode::stringValue).toList();
	}

	// base meets the bar, replays exactly and gives one digest; spike is inside the range
	// but 5% off, with one exact replay and no digest
	private static List<CaseResult> meetingAndMissing() {
		return List.of(result("base", 2, 1, verdict("p50", 10.0)), result("spike", 1, 0, verdict("p95", 21.0)));
	}

	// Python's p50 runs gave 9, 10 and 11, its p95 runs 10, 20 and 30
	private static MetricVerdict verdict(String metric, double javaMedian) {
		List<Double> python = "p50".equals(metric) ? List.of(9.0, 10.0, 11.0) : List.of(10.0, 20.0, 30.0);
		return MetricVerdict.of(metric, python, List.of(javaMedian));
	}

	// a case whose runs that do not replay exactly depart where a cache refused lookups
	private static CaseResult departing(String scenario, int runs) {
		return new CaseResult("queueing-lab", scenario, 2, 2 - runs, 2 - runs,
				new CaseResult.Departing(runs, Map.of(REFUSED, runs)), NONE, List.of(verdict("p50", 10.0)));
	}

	private static CaseResult result(String scenario, int exact, int digests, MetricVerdict verdict) {
		return new CaseResult("queueing-lab", scenario, 2, exact, digests, NOT_DEPARTING, NONE, List.of(verdict));
	}

}
