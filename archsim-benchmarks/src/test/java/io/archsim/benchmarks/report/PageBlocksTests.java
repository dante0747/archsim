package io.archsim.benchmarks.report;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.ResultCopies;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.KeptRun;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The validation page's blocks on the committed results, and on copies of older runs.
 */
class PageBlocksTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final Cpusets CPUSETS = Cpusets.declaredIn(LAYOUT.compose());

	@Test
	void resources_theCommittedResults_showEachPartAtTheTopStepBelowBothKnees() {
		String block = ResourcesBlock.of(latestComparisons());

		assertThat(block)
			.contains("| [pg-mixed](#pg-mixed) | Node.js service | 3,900 | 0.79 | 0.75 | -5% | 4% (3) | "
					+ "148 MiB | 155 MiB | +5% |")
			.contains(
					"| [cache-aside](#cache-aside) | Redis | 5,650 | 0.19 | 0.23 | +23% | 15% (5) | 11 MiB | 31 MiB | "
							+ "+188% |");
	}

	// memory is shown only where it was measured, never a prediction alone
	@Test
	void resources_aPartNotModelled_saysSoAndShowsNoPredictedMemory() {
		String block = ResourcesBlock.of(latestComparisons());

		assertThat(block)
			.contains("| [kafka](#kafka) | Node.js consumer | 2,088 | 0.30 | not modelled | - | - | 182 MiB | - | - |");
	}

	// the methods in the order of their keys, not of their words
	@Test
	void resources_runsMeasuredTwoWays_nameTheMethodsInTheOrderOfTheirKeys() {
		List<JsonNode> comparisons = latestComparisons().subList(0, 2);
		ObjectNode scaled = ((ObjectNode) comparisons.get(0)).deepCopy();
		((ObjectNode) scaled.get("resources")).put("measuredBy", "scaled");

		String block = ResourcesBlock.of(List.of(scaled, comparisons.get(1)));

		assertThat(block).contains("Measured: " + PageTexts.standard().measuredBy("scaled") + "; "
				+ PageTexts.standard().measuredBy("window") + ". Predicted:");
	}

	// k6 kept off the service's hyperthread sibling since the baseline campaign
	@Test
	void environment_theLatestRuns_showEachStacksPinning() {
		String block = EnvironmentBlock.of(latestEnvironments(), CPUSETS);

		assertThat(block).contains("pg-mixed and pg-hot-row: service 1 (`2`), PostgreSQL 2 (`4,5`), k6 4 (`0,1,6,7`)")
			.contains("k6 3 (`0,6,7`)")
			.contains("k6 2 (`6,7`)")
			.doesNotContain("runner's defaults");
	}

	@Test
	void environment_theLatestRunsOnOneMachine_nameItOnce() {
		String block = EnvironmentBlock.of(latestEnvironments(), CPUSETS);

		assertThat(block.split("- CPU: 11th Gen Intel\\(R\\) Core\\(TM\\) i7-1165G7", -1)).hasSize(2);
		assertThat(block).contains("Windows-11-10.0.26300-SP0 (Best performance).");
	}

	@Test
	void environment_aRunOnAnotherOsBuild_getsALineOfItsOwn() {
		Map<String, JsonNode> environments = latestEnvironments();
		ObjectNode older = ((ObjectNode) Objects.requireNonNull(environments.get("pg-mixed"))).deepCopy();
		older.put("os", "Windows-11-10.0.26200-SP0").putNull("powerMode");
		environments.put("pg-mixed", older);

		String block = EnvironmentBlock.of(environments, CPUSETS);

		assertThat(block).contains("- CPU for pg-mixed: 11th Gen Intel(R) Core(TM) i7-1165G7")
			.contains("- CPU for java-mixed, go-mixed, python-mixed, chain, cache-ttl, pg-hot-row, cache-aside and "
					+ "kafka: 11th Gen Intel(R) Core(TM) i7-1165G7");
	}

	@Test
	void environment_aRunBeforeTheCpusetsWereRecorded_showsTheRunnersDefaults() {
		ObjectNode old = ((ObjectNode) Objects.requireNonNull(latestEnvironments().get("pg-mixed"))).deepCopy();
		old.remove("cpusets");

		String block = EnvironmentBlock.of(Map.of("pg-mixed", old), CPUSETS);

		assertThat(block)
			.endsWith(" These are the runner's defaults (`compose.yaml`, the harness's `stacks.json`): the runs "
					+ "shown predate recording the cpusets in `environment.json`.");
	}

	@Test
	void experiment_withoutResults_saysHowToRunIt(@TempDir Path results) {
		String block = ExperimentBlock.of(Experiments.standard().get("pg-mixed"), results);

		assertThat(block)
			.isEqualTo("_No results for pg-mixed yet: run `./gradlew :archsim-benchmarks:run --args=\"postgres\"`._");
	}

	@Test
	void environment_recordedCpusets_areShown() {
		JsonNode environment = JsonText.read("""
				{"cpu": "x", "os": "y", "cpusets": {"api": "0", "postgres": "1-2", "k6": "3-7"}}""");

		String block = EnvironmentBlock.of(Map.of("pg-mixed", environment), CPUSETS);

		assertThat(block).contains("pg-mixed: service 1 (`0`), PostgreSQL 2 (`1-2`), k6 5 (`3-7`)")
			.doesNotContain("runner's defaults");
	}

	@Test
	void experiment_pgMixed_namesItsRealCalibrationRates() {
		String block = ExperimentBlock.of(Experiments.standard().get("pg-mixed"), LAYOUT.results());

		assertThat(block).contains("calibration passes at 50, 793, 2,610 and 3,262 req/s")
			.contains("Measured in the calibration pass at 3,262 req/s")
			.contains("| `serviceTimeByLoad` | x4.03 at 50, x1.31 at 793, x1.04 at 2,610, x1.00 at 3,262 req/s |")
			.doesNotContain("(50 req/s):");
	}

	// its last staircase stopped at its second pass: half the
	// consumers' capacity that pass implied was below the next step
	@Test
	void experiment_kafka_namesTheTwoPassesItsStaircaseClimbed() {
		String block = ExperimentBlock.of(Experiments.standard().get("kafka"), LAYOUT.results());

		assertThat(block).contains("calibration passes at 50 and 406 req/s");
	}

	@Test
	void experiment_aRunUnderProtocolV4_namesItsTwoPasses(@TempDir Path results) throws IOException {
		ResultCopies.copy(LAYOUT.results().resolve("pg-mixed/run-5"), results.resolve("pg-mixed"));

		String block = ExperimentBlock.of(Experiments.standard().get("pg-mixed"), results);

		assertThat(block).contains("calibration passes at 50 and 346 req/s")
			.contains("Measured in the calibration pass at 346 req/s");
	}

	// two repeats at 1,126 req/s had no successful request
	@Test
	void experiment_aRunWithFailedRepeats_explainsThem(@TempDir Path results) throws IOException {
		ResultCopies.copy(LAYOUT.results().resolve("pg-hot-row/run-4"), results.resolve("pg-hot-row"));

		String block = ExperimentBlock.of(Experiments.standard().get("pg-hot-row"), results);

		assertThat(block).contains("| 1,126 (unstable) | - / - | - / - |")
			.contains("At 1,126 req/s, 2 of 3 repeats had no successful request")
			.doesNotContain("0.00");
	}

	private static List<JsonNode> latestComparisons() {
		return LatestRuns.inPageOrder()
			.stream()
			.map((experiment) -> LatestRuns.load(LAYOUT.results(), experiment.id(), KeptRun.COMPARISON))
			.flatMap(Optional::stream)
			.toList();
	}

	private static Map<String, JsonNode> latestEnvironments() {
		Map<String, JsonNode> environments = new LinkedHashMap<>();
		for (Experiment experiment : LatestRuns.inPageOrder()) {
			LatestRuns.load(LAYOUT.results(), experiment.id(), KeptRun.ENVIRONMENT)
				.ifPresent((environment) -> environments.put(experiment.id(), environment));
		}
		return environments;
	}

}
