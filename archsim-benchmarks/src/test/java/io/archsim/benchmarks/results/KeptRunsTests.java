package io.archsim.benchmarks.results;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.archsim.benchmarks.RepositoryLayout;

import static org.assertj.core.api.Assertions.assertThat;

class KeptRunsTests {

	private static final Path RESULTS = RepositoryLayout.current().results();

	/** The runs kept up to the second held-out campaign's third round (23 Nov 2026). */
	private static final int KEPT_RUNS = 109;

	@Test
	void of_theCommittedResults_isEveryKeptRun() {
		List<KeptRun> runs = KeptRuns.of(RESULTS);

		assertThat(runs).hasSize(KEPT_RUNS);
	}

	@Test
	void of_anExperimentsRuns_isOldestFirstAndTheLatestLast() {
		List<String> kafka = KeptRuns.of(RESULTS)
			.stream()
			.filter((run) -> run.experiment().equals("kafka"))
			.map((run) -> RESULTS.relativize(run.directory()).toString().replace('\\', '/'))
			.toList();

		assertThat(kafka).startsWith("kafka/run-1", "kafka/run-2").contains("kafka/run-10").endsWith("kafka");
	}

	@Test
	void name_anOlderRun_isItsExperimentAndItsFolder() {
		KeptRun run = new KeptRun("pg-mixed", RESULTS.resolve("pg-mixed/run-3"));

		assertThat(run.name()).isEqualTo("pg-mixed (run-3)");
	}

	@Test
	void folderUnder_anOlderRun_isItsPathWithForwardSlashes() {
		KeptRun run = new KeptRun("pg-mixed", RESULTS.resolve("pg-mixed/run-3"));

		assertThat(run.folderUnder(RESULTS)).isEqualTo("pg-mixed/run-3");
	}

	// the latest run is in the experiment's own folder
	@Test
	void name_theLatestRun_namesTheExperimentsFolder() {
		KeptRun run = new KeptRun("pg-mixed", RESULTS.resolve("pg-mixed"));

		assertThat(run.name()).isEqualTo("pg-mixed (pg-mixed)");
	}

}
