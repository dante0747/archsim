package io.archsim.benchmarks.baselines;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.designs.Estimates;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.resimulation.CheckStamp;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.KeptRuns;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every kept run's committed {@code baselines.json} is what today's harness and engine
 * write for it, byte for byte, under the file's own stamp: the figures the validation
 * page shows can be computed again from the run's calibration.
 */
class CommittedBaselinesTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final Estimates ESTIMATES = Estimates.onTheEngine();

	private static final Cpusets DECLARED = Cpusets.declaredIn(LAYOUT.compose());

	@TestFactory
	Stream<DynamicTest> of_eachKeptRun_isItsCommittedFileUnderTheFilesStamp() {
		return KeptRuns.of(LAYOUT.results())
			.stream()
			.map((run) -> DynamicTest.dynamicTest(LAYOUT.name(run.directory()),
					() -> assertThat(today(run)).isEqualTo(read(run.file(KeptRun.BASELINES)))));
	}

	// the run's baselines as the harness writes them, stamped as the committed file is
	private static String today(KeptRun run) {
		JsonNode committed = ResultFiles.read(run.file(KeptRun.BASELINES)).orElseThrow();
		CheckStamp stamp = new CheckStamp(committed.get("engineVersion").stringValue(), committed.get("provenance"),
				Clock.fixed(Instant.parse(committed.get("at").stringValue()), ZoneOffset.UTC));
		return ResultFiles.text(new Baselines(ESTIMATES, DECLARED, stamp).of(run).orElseThrow());
	}

	private static String read(Path file) throws IOException {
		return Files.readString(file, StandardCharsets.UTF_8);
	}

}
