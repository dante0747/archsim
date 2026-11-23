package io.archsim.benchmarks.results;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.archsim.benchmarks.RepositoryLayout;

import static org.assertj.core.api.Assertions.assertThat;

class CalibratedRunsTests {

	private static final Path RESULTS = RepositoryLayout.current().results();

	private static final CalibratedRuns COMMITTED = CalibratedRuns.of(RESULTS);

	// the latest run when its check was made, archived by the second held-out campaign
	@Test
	void find_aRunArchivedSinceItsCheck_isItsRunFolder() {
		Optional<KeptRun> run = COMMITTED.find("cache-ttl", "2026-11-04T06:11:13Z");

		assertThat(run).map(KeptRun::directory).contains(RESULTS.resolve("cache-ttl/run-3"));
	}

	@Test
	void find_theLatestRun_isTheExperimentsFolder(@TempDir Path results) throws IOException {
		Path latest = Files.createDirectories(results.resolve("go-pool"));
		Files.writeString(latest.resolve("measured.json"), "{}");
		Files.writeString(latest.resolve("calibration.json"), "{\"at\": \"2026-11-23T00:00:00Z\"}");

		Optional<KeptRun> run = CalibratedRuns.of(results).find("go-pool", "2026-11-23T00:00:00Z");

		assertThat(run).map(KeptRun::directory).contains(latest);
	}

	@Test
	void find_aTimeNoRunWasCalibratedAt_isEmpty() {
		assertThat(COMMITTED.find("cache-ttl", "2000-01-01T00:00:00Z")).isEmpty();
	}

	// the same time on another experiment's stack is another run
	@Test
	void find_anotherExperimentsTime_isEmpty() {
		assertThat(COMMITTED.find("kafka", "2026-11-04T06:11:13Z")).isEmpty();
	}

}
