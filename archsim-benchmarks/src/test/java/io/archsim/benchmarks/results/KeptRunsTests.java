package io.archsim.benchmarks.results;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.archsim.benchmarks.RepositoryLayout;

import static org.assertj.core.api.Assertions.assertThat;

class KeptRunsTests {

	private static final Path RESULTS = RepositoryLayout.current().results();

	/** The runs kept when the Python harness was ported (13 Nov 2026). */
	private static final int KEPT_RUNS = 67;

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

}
