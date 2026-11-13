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
 * {@code accuracy.json} on the committed results: the latest runs are the held-out ones
 * of 3-4 Nov 2026 and the tuned-on ones of 4 Nov.
 */
class CommittedAccuracyTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final JsonNode DATA = AccuracyData
		.of(new ReportSources(LAYOUT.results(), Engines.read(LAYOUT.engines()), Cpusets.declaredIn(LAYOUT.compose())))
		.orElseThrow();

	private static final List<String> HELD_OUT = List.of("java-mixed", "go-mixed", "python-mixed", "chain",
			"cache-ttl");

	private static final List<String> TUNED_ON = List.of("pg-mixed", "pg-hot-row", "cache-aside", "kafka");

	@Test
	void summary_theLatestRuns_rangeFromMinus16ToPlus70Percent() {
		assertThat(DATA.at("/summary/kneeErrorRangeText").stringValue()).isEqualTo("-16% to +70%");
	}

	@Test
	void summary_theLatestRuns_meetTheKneeToleranceForCacheTtlAndTheTunedOnFour() {
		assertThat(ids("kneeWithinTolerance")).containsExactly("cache-ttl", "pg-mixed", "pg-hot-row", "cache-aside",
				"kafka");
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

	// every protocol after v4 was designed with the four's results in view
	@Test
	void summary_theTunedOnFour_areInSample() {
		assertThat(ids("inSample")).isEqualTo(TUNED_ON);
	}

	// no protocol was designed with the held-out experiments' results in view
	@Test
	void summary_theOtherFive_areHeldOut() {
		assertThat(ids("heldOut")).isEqualTo(HELD_OUT);
	}

	@Test
	void summary_everyExperiment_wasRepeated() {
		assertThat(ids("repeated")).hasSize(HELD_OUT.size() + TUNED_ON.size());
	}

	@Test
	void summary_everyExperiment_hasASpread() {
		assertThat(ids("withSpread")).hasSize(HELD_OUT.size() + TUNED_ON.size());
	}

	// pg-mixed's first run of 4 Nov (2,875 req/s,
	// against 4,371 and 4,483) widens its spread
	@Test
	void summary_pgMixed_hasASpreadBeyondItsError() {
		assertThat(ids("spreadExceedsError")).containsExactly("pg-mixed");
	}

	@Test
	void summary_theKeptRuns_areSixtySeven() {
		assertThat(DATA.at("/summary/runs").intValue()).isEqualTo(67);
	}

	@Test
	void summary_theKeptRuns_ranOnOneMachine() {
		assertThat(DATA.at("/summary/machines")).hasSize(1);
	}

	// the 80% range (W28) held the measured knee in 29 of 37 runs: 20 of 22 tuned on
	@Test
	void summary_theRanges_heldAsManyKneesAsTheyClaim() {
		assertThat(DATA.at("/summary/coverage")).isEqualTo(JsonText.read("""
				{"withRange": 37, "inRange": 29, "expectedInRange": 29.6, "nominalCoverage": 0.8,
				 "verdict": "as claimed"}"""));
	}

	// 9 of 15 held out, every held-out miss java-mixed's or go-mixed's
	@Test
	void summary_theHeldOutRanges_heldAsManyKneesAsTheyClaim() {
		assertThat(DATA.at("/summary/coverageHeldOut")).isEqualTo(JsonText.read("""
				{"withRange": 15, "inRange": 9, "expectedInRange": 12.0, "nominalCoverage": 0.8,
				 "verdict": "as claimed"}"""));
	}

	// pg-mixed's two disturbed v5 runs, six older ones,
	// and hot-row's and Kafka's first runs of 4 Nov
	@Test
	void summary_theKeptRuns_haveTenDisturbed() {
		assertThat(DATA.at("/summary/disturbedRuns").intValue()).isEqualTo(10);
	}

	@Test
	void experiment_pgMixed_writesItsKneeAsThePageDoes() {
		assertThat(experiment("pg-mixed").at("/knee/text")).isEqualTo(JsonText.read("""
				{"measured": "4,483", "predicted": "5,203", "error": "+16%", "absError": "16%"}"""));
	}

	// the measured knee's spread pools v5 and v6:
	// the protocol changes the calibration only
	@Test
	void experiment_pgMixed_spreadsOverItsCountedRunsOfBothProtocols() {
		JsonNode spread = experiment("pg-mixed").get("spread");

		assertThat(spread.get("text").stringValue()).isEqualTo("2,875 to 4,495 req/s");
		assertThat(spread.get("plusMinusText").stringValue()).isEqualTo("±22%");
		assertThat(spread.get("disturbed").intValue()).isEqualTo(2);
		assertThat(spread.get("values")).extracting((value) -> value.get("run").stringValue())
			.containsExactly("run-8/", "run-9/", "run-10/", "run-11/", "run-12/", "run-13/", "run-14/", "run-15/",
					"latest");
	}

	// only the runs after the healthcheck fix, and not the disturbed one
	@Test
	void experiment_kafka_spreadsOverItsRunsAfterTheHealthcheckFix() {
		assertThat(experiment("kafka").at("/spread/values")).extracting((value) -> value.get("run").stringValue())
			.containsExactly("run-6/", "run-7/", "run-8/", "run-10/", "latest");
	}

	// criterion 2 is symmetric (ADR-0018): pg-mixed's 3,900 req/s step misses
	@Test
	void experiment_pgMixed_missesThePredictedP95AtItsWorstStep() {
		JsonNode below = experiment("pg-mixed").get("p95BelowKnee");

		assertThat(below.get("within").intValue()).isEqualTo(2);
		assertThat(below.get("steps").intValue()).isEqualTo(3);
		assertThat(below.at("/worst/text/offered").stringValue()).isEqualTo("3,900");
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
	// model's (5,652 req/s), not the measured (6,752)
	@Test
	void resources_cacheAside_comparesAtTheModelsKnee() {
		JsonNode resources = experiment("cache-aside").get("resources");

		assertThat(resources.get("topRps").intValue()).isEqualTo(5650);
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
