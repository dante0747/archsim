package io.archsim.benchmarks.comparison;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.ResultCopies;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.KeptRuns;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed results reduce to the same comparisons: each kept run's
 * {@code comparison.json}, recomputed from its predictions and measurements, is the
 * committed file byte for byte, as the Python harness's {@code run.py compare} wrote it.
 */
class CommittedComparisonsTests {

	private static final Path RESULTS = RepositoryLayout.current().results();

	private static final int PG_HOT_ROW_OLDER_RUNS = 16;

	@TestFactory
	Stream<DynamicTest> comparisonOf_eachKeptRun_isItsCommittedComparisonByteForByte() {
		return KeptRuns.of(RESULTS)
			.stream()
			.map((run) -> DynamicTest.dynamicTest(RESULTS.relativize(run.directory()).toString(), () -> {
				String comparison = ResultFiles.text(Recomparison.comparisonOf(run));

				assertThat(comparison).isEqualTo(Files.readString(run.file(KeptRun.COMPARISON)));
			}));
	}

	@Test
	void recompare_resultsWithoutComparisons_writesEveryKeptRunsComparison(@TempDir Path results) throws IOException {
		ResultCopies.copyWithoutComparisons(RESULTS.resolve("pg-hot-row"), results.resolve("pg-hot-row"));

		List<Path> written = Recomparison.recompare(results);

		List<String> folders = Stream.concat(
				Stream.iterate(1, (number) -> number + 1).limit(PG_HOT_ROW_OLDER_RUNS).map((number) -> "run-" + number),
				Stream.of("pg-hot-row"))
			.toList();
		assertThat(written).map((path) -> Objects.requireNonNull(path.getParent()).getFileName().toString())
			.isEqualTo(folders);
	}

	// two of the three repeats at 1,126 req/s had no successful request
	@Test
	void recompare_run4OfPgHotRow_hasNoLatencyAtTheStepMostRepeatsFailed(@TempDir Path results) throws IOException {
		ResultCopies.copyWithoutComparisons(RESULTS.resolve("pg-hot-row"), results.resolve("pg-hot-row"));

		JsonNode run4 = ResultFiles.read(Recomparison.recompare(results).get(3)).orElseThrow();

		JsonNode step = run4.get("rows")
			.valueStream()
			.filter((row) -> row.get("offeredRps").intValue() == 1126)
			.findFirst()
			.orElseThrow();
		assertThat(step.at("/measured/p95").isNull()).isTrue();
		assertThat(step.get("failedRepeats").intValue()).isEqualTo(2);
	}

}
