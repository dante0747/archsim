package io.archsim.benchmarks.report;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Experiment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The held-out experiments that are registered and not yet measured: the ones no run is
 * kept of, under the committed results and under results of a test's own.
 */
class LatestRunsTests {

	// the newest set is the one no engine has been tuned on
	@Test
	void inPageOrder_twoHeldOutSets_leadsWithTheNewest() {
		assertThat(LatestRuns.inPageOrder()).extracting(Experiment::id)
			.containsExactly("python-cores", "python-wide", "java-hot-row", "go-pool", "cache-miss", "java-mixed",
					"go-mixed", "python-mixed", "chain", "cache-ttl", "pg-mixed", "pg-hot-row", "cache-aside", "kafka");
	}

	@Test
	void awaitingMeasurement_theCommittedResults_isNone() {
		assertThat(LatestRuns.awaitingMeasurement(RepositoryLayout.current().results())).isEmpty();
	}

	// the tuned-on four were never registered: they are not held out
	@Test
	void awaitingMeasurement_noResults_areEveryHeldOutExperimentInThePagesOrder(@TempDir Path results) {
		assertThat(LatestRuns.awaitingMeasurement(results)).extracting(Experiment::id)
			.containsExactly("python-cores", "python-wide", "java-hot-row", "go-pool", "cache-miss", "java-mixed",
					"go-mixed", "python-mixed", "chain", "cache-ttl");
	}

	// its latest run, or one kept before it
	@ParameterizedTest
	@ValueSource(strings = { "go-pool", "go-pool/run-1" })
	void awaitingMeasurement_anExperimentWithAKeptRun_isNotAmongThem(String run, @TempDir Path results)
			throws IOException {
		Runs.keep(results, run);

		assertThat(LatestRuns.awaitingMeasurement(results)).extracting(Experiment::id)
			.doesNotContain("go-pool")
			.contains("python-wide");
	}

}
