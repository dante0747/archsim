package io.archsim.benchmarks.inputs;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The garbage collection a calibration-only trial measured (W21), given to the kept runs
 * whose Node.js service did not report its own, scaled to their requests.
 */
class TrialGcTests {

	@Test
	void trialGc_aTrialsTopPasses_isEachKindsGcPerRequest(@TempDir Path results) {
		writeTrial(results);

		JsonNode gc = TrialGc.of(results, Experiments.standard().get("pg-mixed")).orElseThrow();

		assertThat(gc.get("perRequest")).isEqualTo(JsonText.read("""
				{"major": {"collections": 1.0E-4, "pauseMs": 0.003},
				 "minor": {"collections": 0.006, "pauseMs": 0.005}}"""));
	}

	@Test
	void trialGc_aTrialsTopPasses_namesTheTrialItsTopRateAndItsRequests(@TempDir Path results) {
		writeTrial(results);

		JsonNode gc = TrialGc.of(results, Experiments.standard().get("pg-mixed")).orElseThrow();

		assertThat(List.of(gc.get("trial").stringValue(), gc.get("rateRps").intValue(), gc.get("requests").longValue()))
			.containsExactly("2026-10-31-pg-mixed-w21", 1000, 40_000L);
	}

	@Test
	void trialGc_anExperimentWithoutATrial_isEmpty(@TempDir Path results) {
		writeTrial(results);

		assertThat(TrialGc.of(results, Experiments.standard().get("kafka"))).isEmpty();
	}

	@Test
	void give_aKeptRunsTopPass_carriesTheTrialsGcScaledToItsRequests(@TempDir Path results) {
		writeTrial(results);
		JsonNode gc = TrialGc.of(results, Experiments.standard().get("pg-mixed")).orElseThrow();

		JsonNode given = TrialGc.give(JsonText.read("""
				{"passes": {"low": {}, "high": {"service": {"requests": 20000}}},
				 "replicates": [{"high": {"service": {"requests": 20000}}}]}"""), gc);

		JsonNode service = given.get("replicates").get(0).get("high").get("service");
		assertThat(service.at("/gc/byKind/major/collections").doubleValue()).isCloseTo(2, within(1e-9));
		assertThat(service.at("/gc/byKind/major/pauseMs").doubleValue()).isCloseTo(60, within(1e-9));
		assertThat(service.at("/gc/source").stringValue()).contains("trial 2026-10-31-pg-mixed-w21");
	}

	@Test
	void give_aKeptRunsTopPass_isSpacedAsTheTrialsCycles(@TempDir Path results) {
		writeTrial(results);
		JsonNode gc = TrialGc.of(results, Experiments.standard().get("pg-mixed")).orElseThrow();
		JsonNode given = TrialGc.give(JsonText.read("""
				{"passes": {"low": {}, "high": {"service": {"requests": 20000}}},
				 "replicates": [{"high": {"service": {"requests": 20000}}}]}"""), gc);

		GcInputs.Node inputs = GcInputs.node(given.get("replicates").get(0).get("high").get("service"));

		assertThat(inputs.inputs().get("gcEveryRequests").get("value").longValue()).isEqualTo(10_000);
		assertThat(inputs.inputs().get("gcPauseMs").get("how").stringValue()).contains("(per request as trial");
	}

	@Test
	void give_aServiceThatReportedItsOwnGc_keepsIt(@TempDir Path results) {
		writeTrial(results);
		JsonNode gc = TrialGc.of(results, Experiments.standard().get("pg-mixed")).orElseThrow();
		JsonNode own = JsonText.read("""
				{"service": {"requests": 5000,
				             "gc": {"byKind": {"major": {"collections": 1, "pauseMs": 5.0}}}}}""");

		ObjectNode calibration = JsonText.object("{\"passes\": {}}");
		((ObjectNode) calibration.get("passes")).set("high", own);

		JsonNode given = TrialGc.give(calibration, gc);

		assertThat(given.get("passes").get("high")).isEqualTo(own);
	}

	// two top passes: 1 + 3 mark-compacts (30 + 90 ms) and 60 + 180 scavenges over 40,000
	// requests, the staircase's top at 1,000 req/s
	private static void writeTrial(Path results) {
		Path trial = results.resolve("trials/2026-10-31-pg-mixed-w21/calibration.json");
		ResultFiles.write(trial, JsonText.read("""
				{"staircaseRps": [50, 1000], "replicates": [
				  {"high": {"service": {"requests": 10000, "gc": {"byKind": {
				    "major": {"collections": 1, "pauseMs": 30.0},
				    "minor": {"collections": 60, "pauseMs": 50.0}}}}}},
				  {"high": {"service": {"requests": 30000, "gc": {"byKind": {
				    "major": {"collections": 3, "pauseMs": 90.0},
				    "minor": {"collections": 180, "pauseMs": 150.0}}}}}}]}"""));
	}

}
