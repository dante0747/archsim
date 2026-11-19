package io.archsim.benchmarks.baselines;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.designs.Estimates;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.resimulation.CheckStamp;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.version.EngineVersions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A kept run's baselines, on runs the committed results keep in {@code run-N/} folders,
 * which no later campaign changes: each arithmetic and extrapolated figure checked
 * against the same arithmetic done apart from the harness on the run's
 * {@code calibration.json}.
 */
class BaselinesTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-13T12:34:56Z"), ZoneOffset.UTC);

	private static final Baselines BASELINES = new Baselines(Estimates.onTheEngine(),
			Cpusets.declaredIn(LAYOUT.compose()),
			new CheckStamp(EngineVersions.ENGINE, JsonText.read("{\"commit\": \"abc\"}"), NOON));

	// the service's core at 0.1962 ms a request; its measured CPU reaches the core at
	// 5,188
	@Test
	void of_aKeptRun_estimatesItsKneeThreeWays() {
		JsonNode baselines = baselines("pg-mixed", "run-15").get("baselines");

		assertThat(baselines.at("/bottleneckArithmetic/kneeRps").doubleValue()).isEqualTo(5096.7);
		assertThat(baselines.at("/analyticEstimate/kneeRps")).isEqualTo(PythonValues.integer(5000));
		assertThat(baselines.at("/utilisationExtrapolation/kneeRps").doubleValue()).isEqualTo(5188.0);
	}

	@Test
	void of_aKeptRun_namesWhatBoundsEachEstimate() {
		JsonNode baselines = baselines("pg-mixed", "run-15").get("baselines");

		assertThat(baselines.at("/bottleneckArithmetic/limitedBy"))
			.isEqualTo(JsonText.read("{\"part\": \"api\", \"limit\": \"cpu\"}"));
		assertThat(baselines.at("/analyticEstimate/basis").stringValue()).isEqualTo("p95");
		assertThat(baselines.at("/utilisationExtrapolation/limitedBy").stringValue()).isEqualTo("api");
	}

	// a write and its flush, 1.1543 ms together, hold the one row's lock
	@Test
	void of_aRunWhoseWritesUpdateOneRow_isBoundByTheRowsLock() {
		JsonNode arithmetic = baselines("pg-hot-row", "run-13").at("/baselines/bottleneckArithmetic");

		assertThat(arithmetic.get("limitedBy")).isEqualTo(JsonText.read("{\"part\": \"db\", \"limit\": \"rowLock\"}"));
		assertThat(arithmetic.get("kneeRps").doubleValue()).isEqualTo(866.3);
	}

	// 23,776 of the top pass's 98,761 lookups missed
	@Test
	void of_aRunWithACache_sendsItsDatabaseTheTopPassesMisses() {
		JsonNode arithmetic = baselines("cache-aside", "run-10").at("/baselines/bottleneckArithmetic");

		assertThat(arithmetic.get("cacheMisses")).isEqualTo(JsonText.read("""
				{"share": 0.240743, "how": "the top calibration pass's misses over its lookups: 23776 of 98761"}
				"""));
		assertThat(arithmetic.get("kneeRps").doubleValue()).isEqualTo(5505.8);
	}

	@Test
	void of_aRunWithoutACache_recordsNoCacheMisses() {
		JsonNode arithmetic = baselines("pg-mixed", "run-15").at("/baselines/bottleneckArithmetic");

		assertThat(arithmetic.has("cacheMisses")).isFalse();
	}

	// the consumers' 1,718 messages a second are below the producer's 2,752 req/s
	@Test
	void of_aRunWithAQueue_isNotBoundByTheWorkBehindIt() {
		JsonNode baselines = baselines("kafka", "run-10").get("baselines");

		assertThat(baselines.at("/bottleneckArithmetic/kneeRps").doubleValue()).isEqualTo(2751.9);
		assertThat(baselines.at("/bottleneckArithmetic/limits/1")).isEqualTo(JsonText.read("""
				{"part": "kafka", "limit": "consumers", "units": 3, "msPerRequest": 1.746436, "capacityRps": 1717.8,
				 "onRequestPath": false}
				"""));
		assertThat(baselines.at("/utilisationExtrapolation/kneeRps").doubleValue()).isEqualTo(2664.6);
	}

	// the first Kafka runs calibrated no consumer concurrency: one message at a time
	@Test
	void of_aQueueCalibratedWithoutItsConsumersConcurrency_listsTheirLimitAtOneSlot() {
		JsonNode arithmetic = baselines("kafka", "run-1").at("/baselines/bottleneckArithmetic");

		assertThat(arithmetic.at("/limits/1")).isEqualTo(JsonText.read("""
				{"part": "kafka", "limit": "consumers", "units": 1, "msPerRequest": 4.310249, "capacityRps": 232.0,
				 "onRequestPath": false}
				"""));
	}

	// the estimate's p95 is the producer's requests': the consumers work behind the queue
	@Test
	void of_aRunWithAQueue_recordsThatTheEstimateCallsItOverloadedAtItsKnee() {
		JsonNode estimate = baselines("kafka", "run-10").at("/baselines/analyticEstimate");

		assertThat(estimate.get("kneeRps")).isEqualTo(PythonValues.integer(2675));
		assertThat(estimate.at("/atKnee/overloaded").booleanValue()).isTrue();
		assertThat(estimate.at("/atKnee/summary").stringValue()).isEqualTo(
				"Overloaded at 2,675 req/s: Kafka needs 156% of its capacity; it saturates at about 1,717 req/s.");
	}

	@Test
	void of_aRunCalibratedBeforeTheStaircase_hasNoExtrapolationAndSaysWhy() {
		JsonNode extrapolation = baselines("pg-mixed", "run-2").at("/baselines/utilisationExtrapolation");

		assertThat(PythonValues.isNone(extrapolation.get("kneeRps"))).isTrue();
		assertThat(extrapolation.get("why").stringValue()).isEqualTo(UtilisationExtrapolation.NO_STAIRCASE);
	}

	// its two passes calibrated the inputs both other estimates read
	@Test
	void of_aRunCalibratedBeforeTheStaircase_stillHasItsArithmeticAndItsAnalyticEstimate() {
		JsonNode baselines = baselines("pg-mixed", "run-2").get("baselines");

		assertThat(baselines.at("/bottleneckArithmetic/kneeRps").doubleValue()).isEqualTo(2315.2);
		assertThat(baselines.at("/analyticEstimate/kneeRps")).isEqualTo(PythonValues.integer(2250));
	}

	@Test
	void of_aKeptRun_saysWhatItIsAndWhatProducedIt() {
		ObjectNode baselines = baselines("pg-mixed", "run-15");

		assertThat(baselines.get("experiment").stringValue()).isEqualTo("pg-mixed");
		assertThat(baselines.get("calibratedAt").stringValue()).isEqualTo("2026-11-04T09:35:52Z");
		assertThat(baselines.get("kind").stringValue()).isEqualTo("baselines");
		assertThat(baselines.get("at").stringValue()).isEqualTo("2026-11-13T12:34:56Z");
		assertThat(baselines.get("engineVersion").stringValue()).isEqualTo(EngineVersions.ENGINE);
		assertThat(baselines.get("provenance")).isEqualTo(JsonText.read("{\"commit\": \"abc\"}"));
		assertThat(baselines.get("objective")).isEqualTo(JsonText.read("{\"p95Ms\": 50.0, \"minSuccess\": 0.99}"));
	}

	// the rules were fixed on 19 Nov 2026, with this run's results in view
	@Test
	void of_aRunCalibratedBeforeTheRulesWereFixed_saysTheyWereDesignedAfterItsMeasurements() {
		ObjectNode baselines = baselines("pg-mixed", "run-15");

		assertThat(baselines.get("rulesFixed").stringValue()).isEqualTo("2026-11-19");
		assertThat(baselines.get("designedAfterMeasurements").booleanValue()).isTrue();
		assertThat(baselines.get("how").stringValue()).endsWith(Baselines.RULES_AFTER_THE_RUN);
	}

	// a later campaign's run: the rules predate what it measures
	@Test
	void of_aRunCalibratedAfterTheRulesWereFixed_saysTheRulesPredateItsMeasurement(@TempDir Path folder) {
		ObjectNode calibration = (ObjectNode) ResultFiles
			.read(LAYOUT.results().resolve("pg-mixed/run-15/calibration.json"))
			.orElseThrow();
		ResultFiles.write(folder.resolve("calibration.json"), calibration.put("at", "2026-11-20T08:00:00Z"));

		ObjectNode baselines = BASELINES.of(new KeptRun("pg-mixed", folder)).orElseThrow();

		assertThat(baselines.get("designedAfterMeasurements").booleanValue()).isFalse();
		assertThat(baselines.get("how").stringValue()).endsWith(Baselines.RULES_BEFORE_THE_RUN);
	}

	// no measured step is read: the calibration alone, and compose.yaml's cpusets
	// the rules may have changed later that day, so the run is not shown to follow them
	@Test
	void of_aRunCalibratedOnTheDayTheRulesWereFixed_countsAsMeasuredBeforeThem(@TempDir Path folder) {
		ObjectNode calibration = (ObjectNode) ResultFiles
			.read(LAYOUT.results().resolve("pg-mixed/run-15/calibration.json"))
			.orElseThrow();
		ResultFiles.write(folder.resolve("calibration.json"), calibration.put("at", "2026-11-19T08:00:00Z"));

		ObjectNode baselines = BASELINES.of(new KeptRun("pg-mixed", folder)).orElseThrow();

		assertThat(baselines.get("designedAfterMeasurements").booleanValue()).isTrue();
		assertThat(baselines.get("how").stringValue()).endsWith(Baselines.RULES_AFTER_THE_RUN);
	}

	// no measured step is read: the calibration alone, and compose.yaml's cpusets
	@Test
	void of_aCalibrationAlone_estimatesTheKneeWithoutAnyMeasurement(@TempDir Path folder) throws IOException {
		Files.copy(LAYOUT.results().resolve("pg-mixed/run-15/calibration.json"), folder.resolve("calibration.json"));

		ObjectNode baselines = BASELINES.of(new KeptRun("pg-mixed", folder)).orElseThrow();

		assertThat(baselines.at("/baselines/utilisationExtrapolation/kneeRps").doubleValue()).isEqualTo(5188.0);
		assertThat(baselines.at("/baselines/utilisationExtrapolation/cpusFrom").stringValue())
			.isEqualTo(ContainerCpus.DECLARED);
	}

	@Test
	void of_aRunWithoutACalibration_isEmpty(@TempDir Path folder) {
		Optional<ObjectNode> baselines = BASELINES.of(new KeptRun("pg-mixed", folder));

		assertThat(baselines).isEmpty();
	}

	private static ObjectNode baselines(String experiment, String run) {
		return BASELINES.of(new KeptRun(experiment, LAYOUT.results().resolve(experiment).resolve(run))).orElseThrow();
	}

}
