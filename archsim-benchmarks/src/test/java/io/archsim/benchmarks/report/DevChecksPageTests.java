package io.archsim.benchmarks.report;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.RepositoryLayout;

import static org.assertj.core.api.Assertions.assertThat;

class DevChecksPageTests {

	private static final Path RESULTS = RepositoryLayout.current().results();

	// engine 0.14.0 against every kept run, never the held-out experiments: 28 kept runs
	// less the two calibrated from one pass
	@Test
	void checks_engine0140_checkedTheTunedOnRunsCalibratedFromTwoPassesOrMore() {
		Map<String, Map<String, JsonNode>> byExperiment = DevChecksPage.checks(RESULTS)
			.getOrDefault("0.14.0", Map.of());

		assertThat(byExperiment).containsOnlyKeys("pg-mixed", "pg-hot-row", "cache-aside", "kafka");
		assertThat(byExperiment.values().stream().mapToInt(Map::size).sum()).isEqualTo(26);
	}

	// the protocols since v3 derive the same inputs today, and simulate what their
	// predictions said, step by step; v2 had no anchor
	@Test
	void checks_engine0140_reproducesThePredictionsOfEveryRunWhoseInputsItDerivesAgain() {
		List<JsonNode> checks = DevChecksPage.checks(RESULTS)
			.getOrDefault("0.14.0", Map.of())
			.values()
			.stream()
			.flatMap((runs) -> runs.values().stream())
			.toList();

		assertThat(checks).filteredOn((check) -> check.get("inputsAsCalibrated").booleanValue())
			.allMatch((check) -> check.at("/sameLatencyAsPredictions/steps")
				.equals(check.at("/sameLatencyAsPredictions/of")));
		assertThat(checks).filteredOn((check) -> !check.get("inputsAsCalibrated").booleanValue())
			.extracting((check) -> check.get("results").stringValue())
			.containsExactlyInAnyOrder("pg-hot-row/run-2", "pg-mixed/run-2");
	}

	@Test
	void text_theCommittedChecks_listEachRunWithEachEnginesKnee() {
		String page = DevChecksPage.text(RESULTS);

		assertThat(page)
			.contains("| pg-hot-row | `pg-hot-row/run-5/` | 2026-10-30T10:32:56Z | yes | 814 | yes | 519 (-36%) |");
	}

	// listed, but left out of the medians: 19 of the 26 count
	@Test
	void text_aDisturbedRun_isListedButNotCounted() {
		String page = DevChecksPage.text(RESULTS);

		assertThat(page).contains("| `pg-mixed/run-6/` | 2026-10-30T21:30:44Z | yes | 1,089 | no: disturbed |")
			.contains(
					"Engine 0.14.0: median absolute knee error 20% over the latest run it checked of each experiment, "
							+ "24% over all 19 counted runs.");
	}

	// the v6 campaign's runs, checked with engine 0.16.0 only
	@Test
	void text_theFrozenEngine_hasItsMedianError() {
		String page = DevChecksPage.text(RESULTS);

		assertThat(page)
			.contains("Engine 0.16.0: median absolute knee error 15% over the latest run it checked of each "
					+ "experiment, 19% over all 31 counted runs.");
	}

	// the host machine is the designs' default since ADR-0095, not a switch to name
	@Test
	void text_anEngineOnAHostMachine_saysHowTheMachineWasFitted() {
		String page = DevChecksPage.text(RESULTS);

		assertThat(page).contains("- Engine 1.6.0-law-host-refit ran the designs on a host machine: contention check "
				+ "2026-11-20T17:00:31Z: 4 physical cores of 8 threads");
	}

	@Test
	void text_noChecks_saysSo(@TempDir Path empty) {
		assertThat(DevChecksPage.text(empty)).contains("_No development checks yet._");
	}

}
