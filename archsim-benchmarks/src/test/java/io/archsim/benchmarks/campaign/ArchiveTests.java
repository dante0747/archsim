package io.archsim.benchmarks.campaign;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An experiment's latest results kept in its next {@code run-N/} before a new run.
 */
class ArchiveTests {

	@Test
	void keep_latestResults_movesThemIntoTheNextRun(@TempDir Path results) throws IOException {
		latest(results.resolve("pg-hot-row"));
		Files.createDirectories(results.resolve("pg-hot-row/run-1"));
		Files.createDirectories(results.resolve("pg-hot-row/run-13"));

		Path kept = Archive.keep(results, "pg-hot-row").orElseThrow();

		assertThat(kept).isEqualTo(results.resolve("pg-hot-row/run-14"));
	}

	@Test
	void keep_latestResults_leavesNoneBehind(@TempDir Path results) throws IOException {
		latest(results.resolve("kafka"));

		Path kept = Archive.keep(results, "kafka").orElseThrow();

		assertThat(kept.resolve("comparison.json")).hasContent("{}");
		assertThat(results.resolve("kafka/measured.json")).doesNotExist();
	}

	@Test
	void keep_anExperimentWithoutRuns_startsAtRun1(@TempDir Path results) throws IOException {
		latest(results.resolve("kafka"));

		assertThat(Archive.keep(results, "kafka")).contains(results.resolve("kafka/run-1"));
	}

	@Test
	void keep_nothingLeft_keepsNothing(@TempDir Path results) throws IOException {
		latest(results.resolve("kafka"));
		Archive.keep(results, "kafka");

		assertThat(Archive.keep(results, "kafka")).isEmpty();
	}

	@Test
	void keep_noResults_keepsNothing(@TempDir Path results) {
		assertThat(Archive.keep(results, "cache-aside")).isEmpty();
	}

	private static void latest(Path experiment) throws IOException {
		Files.createDirectories(experiment);
		Files.writeString(experiment.resolve("measured.json"), "{}");
		Files.writeString(experiment.resolve("comparison.json"), "{}");
	}

}
