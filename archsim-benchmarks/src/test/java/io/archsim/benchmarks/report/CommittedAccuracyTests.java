package io.archsim.benchmarks.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.experiments.Engines;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code accuracy.json} on the committed results: the latest runs are the third round of
 * the second held-out campaign (23 Nov 2026, engine 1.6.0), every experiment in it.
 */
class CommittedAccuracyTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final JsonNode DATA = AccuracyData
		.of(new ReportSources(LAYOUT.results(), Engines.read(LAYOUT.engines()), Cpusets.declaredIn(LAYOUT.compose())))
		.orElseThrow();

	private static final List<String> HELD_OUT = List.of("python-cores", "python-wide", "java-hot-row", "go-pool",
			"cache-miss");

	private static final List<String> TUNED_ON = List.of("java-mixed", "go-mixed", "python-mixed", "chain", "cache-ttl",
			"pg-mixed", "pg-hot-row", "cache-aside", "kafka");

	@Test
	void summary_theLatestRuns_rangeFromMinus31ToPlus15Percent() {
		assertThat(DATA.at("/summary/kneeErrorRangeText").stringValue()).isEqualTo("-31% to +15%");
	}

	// pg-mixed's third round stopped its calibration early, at 743 req/s
	@Test
	void summary_theLatestRuns_meetTheKneeToleranceForAllButPgMixed() {
		assertThat(ids("kneeWithinTolerance")).containsExactly("python-cores", "python-wide", "java-hot-row", "go-pool",
				"cache-miss", "java-mixed", "go-mixed", "python-mixed", "chain", "cache-ttl", "pg-hot-row",
				"cache-aside", "kafka");
	}

	@Test
	void experiments_theLatestRuns_werePredictedByTheFrozenEngine() {
		assertThat(DATA.get("experiments")).hasSize(HELD_OUT.size() + TUNED_ON.size())
			.allMatch((experiment) -> "1.6.0".equals(experiment.get("engineVersion").stringValue()));
	}

	@Test
	void summary_theLatestRuns_validateNoExperiment() {
		assertThat(ids("validated")).isEmpty();
	}

	@Test
	void summary_theLatestRuns_validateNoTechnology() {
		assertThat(DATA.at("/summary/technologies"))
			.noneMatch((technology) -> technology.get("validated").booleanValue());
	}

	// engine 1.6.0 lists the first nine as tuned on
	@Test
	void summary_theTunedOnNine_areInSample() {
		assertThat(ids("inSample")).isEqualTo(TUNED_ON);
	}

	// registered before engine 1.6.0 was frozen, sealed until it had predicted them
	@Test
	void summary_theSecondSet_isHeldOut() {
		assertThat(ids("heldOut")).isEqualTo(HELD_OUT);
	}

	// Kafka's third round was disturbed, which leaves it two runs alike
	@Test
	void summary_everyExperimentButKafka_wasRepeated() {
		assertThat(ids("repeated")).hasSize(HELD_OUT.size() + TUNED_ON.size() - 1).doesNotContain("kafka");
	}

	@Test
	void summary_everyExperiment_hasASpread() {
		assertThat(ids("withSpread")).hasSize(HELD_OUT.size() + TUNED_ON.size());
	}

	@Test
	void summary_fourExperiments_haveASpreadBeyondTheirError() {
		assertThat(ids("spreadExceedsError")).containsExactly("python-cores", "java-mixed", "go-mixed", "pg-hot-row");
	}

	@Test
	void summary_theKeptRuns_areAHundredAndNine() {
		assertThat(DATA.at("/summary/runs").intValue()).isEqualTo(109);
	}

	@Test
	void summary_theKeptRuns_ranOnOneMachine() {
		assertThat(DATA.at("/summary/machines")).hasSize(1);
	}

	// the 80% range held the measured knee in 37 of the 41 counted runs: 25 of 26 tuned
	// on
	@Test
	void summary_theRanges_heldAsManyKneesAsTheyClaim() {
		assertThat(DATA.at("/summary/coverage")).isEqualTo(JsonText.read("""
				{"withRange": 41, "inRange": 37, "expectedInRange": 32.8, "nominalCoverage": 0.8,
				 "verdict": "as claimed"}"""));
	}

	// 12 of 15 held out: python-wide missed twice, cache-miss once
	@Test
	void summary_theHeldOutRanges_heldAsManyKneesAsTheyClaim() {
		assertThat(DATA.at("/summary/coverageHeldOut")).isEqualTo(JsonText.read("""
				{"withRange": 15, "inRange": 12, "expectedInRange": 12.0, "nominalCoverage": 0.8,
				 "verdict": "as claimed"}"""));
	}

	// pg-mixed's two disturbed v5 runs, six older ones, hot-row's
	// and Kafka's first runs of 4 Nov, and Kafka's third round
	@Test
	void summary_theKeptRuns_haveElevenDisturbed() {
		assertThat(DATA.at("/summary/disturbedRuns").intValue()).isEqualTo(11);
	}

	@Test
	void experiment_pgMixed_writesItsKneeAsThePageDoes() {
		assertThat(experiment("pg-mixed").at("/knee/text")).isEqualTo(JsonText.read("""
				{"measured": "4,934", "predicted": "3,401", "error": "-31%", "absError": "31%"}"""));
	}

	// the measured knee's spread pools v5, v6 and v7:
	// the protocol changes the calibration only
	@Test
	void experiment_pgMixed_spreadsOverItsCountedRunsOfEveryProtocol() {
		JsonNode spread = experiment("pg-mixed").get("spread");

		assertThat(spread.get("text").stringValue()).isEqualTo("2,875 to 4,934 req/s");
		assertThat(spread.get("plusMinusText").stringValue()).isEqualTo("±26%");
		assertThat(spread.get("disturbed").intValue()).isEqualTo(2);
		assertThat(spread.get("values")).extracting((value) -> value.get("run").stringValue())
			.containsExactly("run-8/", "run-9/", "run-10/", "run-11/", "run-12/", "run-13/", "run-14/", "run-15/",
					"run-16/", "run-17/", "run-18/", "latest");
	}

	// only the runs after the healthcheck fix, and not the two disturbed
	@Test
	void experiment_kafka_spreadsOverItsRunsAfterTheHealthcheckFix() {
		assertThat(experiment("kafka").at("/spread/values")).extracting((value) -> value.get("run").stringValue())
			.containsExactly("run-6/", "run-7/", "run-8/", "run-10/", "run-11/", "run-12/", "run-13/");
	}

	// criterion 2 is symmetric (ADR-0018): pg-mixed's worst step is at 3,740 req/s
	@Test
	void experiment_pgMixed_missesThePredictedP95AtItsWorstStep() {
		JsonNode below = experiment("pg-mixed").get("p95BelowKnee");

		assertThat(below.get("within").intValue()).isEqualTo(4);
		assertThat(below.get("steps").intValue()).isEqualTo(7);
		assertThat(below.at("/worst/text/offered").stringValue()).isEqualTo("3,740");
	}

	@Test
	void experiment_pgMixed_recordsItsMachine() {
		JsonNode environment = experiment("pg-mixed").get("environment");

		assertThat(environment.get("powerMode").stringValue()).isEqualTo("Best performance");
		assertThat(environment.get("dockerCpus").intValue()).isEqualTo(8);
		assertThat(environment.get("dockerMemoryGiB").doubleValue()).isEqualTo(7.6);
		assertThat(environment.get("cpusetsRecorded").booleanValue()).isTrue();
	}

	// the history marks the disturbed runs, which still show their own knee
	@Test
	void history_pgMixed_marksItsDisturbedRuns() {
		assertThat(DATA.at("/history/pg-mixed")).filteredOn((run) -> !run.at("/checks/disturbed").isEmpty())
			.extracting((run) -> run.get("dir").stringValue())
			.containsExactly("run-2/", "run-6/", "run-7/");
	}

	// the v6 campaign measured Kafka without the healthcheck
	@Test
	void summary_theLatestRuns_haveNoHarnessCaveat() {
		assertThat(ids("caveats")).isEmpty();
	}

	// the history still marks Kafka's runs before the healthcheck's fix
	@Test
	void history_kafka_marksTheRunsBeforeTheHealthcheckFix() {
		assertThat(DATA.at("/history/kafka")).filteredOn((run) -> run.get("harnessCaveat").booleanValue())
			.extracting((run) -> run.get("dir").stringValue())
			.containsExactly("run-1/", "run-2/", "run-3/", "run-4/", "run-5/");
	}

	@Test
	void resources_everyLatestRun_wasMeasuredInTheWindowAndPredictedBeforehand() {
		Map<String, List<Object>> how = new LinkedHashMap<>();
		DATA.get("experiments")
			.forEach((experiment) -> how.put(experiment.get("id").stringValue(),
					List.of(experiment.at("/resources/measuredBy").stringValue(),
							experiment.at("/resources/predictedBy").stringValue(),
							experiment.at("/resources/memoryMeasured").booleanValue())));

		assertThat(how.values()).containsOnly(List.of("window", "predictions", true));
	}

	// the top step at or below both knees: the
	// model's (5,460 req/s), not the measured (6,335)
	@Test
	void resources_cacheAside_comparesAtTheModelsKnee() {
		JsonNode resources = experiment("cache-aside").get("resources");

		assertThat(resources.get("topRps").intValue()).isEqualTo(5450);
		assertThat(resources.get("parts")).extracting((part) -> part.get("container").stringValue())
			.containsExactly("api", "postgres", "redis");
	}

	private static List<String> ids(String list) {
		return DATA.at("/summary/" + list).valueStream().map(JsonNode::stringValue).toList();
	}

	private static JsonNode experiment(String id) {
		return DATA.get("experiments")
			.valueStream()
			.filter((experiment) -> experiment.get("id").stringValue().equals(id))
			.findFirst()
			.orElseThrow();
	}

}
