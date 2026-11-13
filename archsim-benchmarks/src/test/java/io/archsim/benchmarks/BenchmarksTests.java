package io.archsim.benchmarks;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarksTests {

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-13T12:34:56Z"), ZoneOffset.UTC);

	/** cache-aside's kept runs: run-1/ to run-10/ and the latest. */
	private static final int CACHE_ASIDE_RUNS = 11;

	@Test
	void compare_keptRunsWithoutComparisons_writesEachRunsComparison(@TempDir Path root) throws IOException {
		Path cacheAside = root.resolve("benchmarks/results/cache-aside");
		ResultCopies.copyWithoutComparisons(RepositoryLayout.current().results().resolve("cache-aside"), cacheAside);

		commandLine(root, new StringWriter()).execute("compare");

		assertThat(ResultCopies.comparisons(cacheAside)).hasSize(CACHE_ASIDE_RUNS);
	}

	@Test
	void compare_keptRuns_logsEachFileItWrote(@TempDir Path root) throws IOException {
		Path cacheAside = root.resolve("benchmarks/results/cache-aside");
		ResultCopies.copyWithoutComparisons(RepositoryLayout.current().results().resolve("cache-aside"), cacheAside);
		StringWriter out = new StringWriter();

		int exitCode = commandLine(root, out).execute("compare");

		List<String> lines = out.toString().lines().toList();
		assertThat(exitCode).isZero();
		assertThat(lines).hasSize(CACHE_ASIDE_RUNS)
			.startsWith("[12:34:56] wrote benchmarks/results/cache-aside/run-1/comparison.json")
			.endsWith("[12:34:56] wrote benchmarks/results/cache-aside/comparison.json");
	}

	@Test
	void resources_predictionsThatHaveResources_onlyRecomputesTheComparisons(@TempDir Path root) throws IOException {
		Path cacheTtl = root.resolve("benchmarks/results/cache-ttl");
		ResultCopies.copyWithoutComparisons(RepositoryLayout.current().results().resolve("cache-ttl"), cacheTtl);
		StringWriter out = new StringWriter();

		int exitCode = commandLine(root, out).execute("resources");

		assertThat(exitCode).isZero();
		assertThat(out.toString().lines()).containsExactly(
				"[12:34:56] wrote benchmarks/results/cache-ttl/run-1/comparison.json",
				"[12:34:56] wrote benchmarks/results/cache-ttl/run-2/comparison.json",
				"[12:34:56] wrote benchmarks/results/cache-ttl/comparison.json");
	}

	@Test
	void resimulate_oneExperiment_writesItsKeptRunsChecks(@TempDir Path root) throws IOException {
		Path run = root.resolve("benchmarks/results/kafka/run-2");
		ResultCopies.copy(RepositoryLayout.current().results().resolve("kafka/run-2"), run);
		StringWriter out = new StringWriter();

		int exitCode = commandLine(root, out).execute("resimulate", "--only", "kafka");

		assertThat(exitCode).isZero();
		assertThat(out.toString().lines()).last()
			.isEqualTo("[12:34:56] wrote benchmarks/results/dev/1.0.0/kafka/2026-10-30T11-20-01Z.json");
	}

	@ParameterizedTest
	@ValueSource(strings = { "--label=0.16.0-java", "--only=no-such-experiment" })
	void resimulate_aLabelOrAnExperimentItCannotTake_isRefusedBeforeItRuns(String option, @TempDir Path root) {
		StringWriter out = new StringWriter();

		int exitCode = commandLine(root, out).execute("resimulate", option);

		assertThat(exitCode).isEqualTo(2);
		assertThat(root.resolve("benchmarks/results/dev")).doesNotExist();
	}

	// no kept runs: nothing to fit on, and every fold counts no run
	@Test
	void rangeFit_noKeptRuns_writesAFitOfNoRunsAndSaysSo(@TempDir Path root) throws IOException {
		StringWriter out = new StringWriter();

		int exitCode = commandLine(root, out).execute("range-fit");

		assertThat(exitCode).isZero();
		assertThat(ResultFiles.read(root.resolve("benchmarks/knee-range.json")).orElseThrow().get("runs").intValue())
			.isZero();
		assertThat(out.toString().lines()).startsWith(
				"[12:34:56] range fit, engine 1.0.0: 0 calibrations, 0 with engine 1.0.0's development checks, 0 to "
						+ "derive (or in the cache)")
			.endsWith("[12:34:56] range fit: inSample: 0 of 0 runs inside the 80% range",
					"[12:34:56] range fit: leaveRunOut: 0 of 0 runs inside the 80% range",
					"[12:34:56] range fit: leaveExperimentOut: 0 of 0 runs inside the 80% range",
					"[12:34:56] wrote benchmarks/knee-range.json");
	}

	@Test
	void rangeFit_aCacheThatHoldsResults_saysItReusesThem(@TempDir Path root) {
		ResultFiles.write(rangeFitCache(root), JsonText.read("{\"sens|pg-mixed|0\": 1700.0}"));
		StringWriter out = new StringWriter();

		commandLine(root, out).execute("range-fit");

		assertThat(out.toString().lines()).first()
			.isEqualTo("[12:34:56] range fit: reusing the 1 results " + rangeFitCache(root) + " holds");
	}

	@Test
	void execute_withoutACommand_isRefused() {
		int exitCode = commandLine(Path.of("."), new StringWriter()).execute();

		assertThat(exitCode).isEqualTo(2);
	}

	private static CommandLine commandLine(Path root, StringWriter out) {
		PrintWriter writer = new PrintWriter(out, true);
		return Benchmarks.commandLine(Setups.onThisMachine(new RepositoryLayout(root), new HarnessLog(writer, NOON)))
			.setOut(writer)
			.setErr(writer);
	}

	// the file range-fit keeps its finished work in: the test's, not the machine's
	private static Path rangeFitCache(Path root) {
		return root.resolve("range-fit-cache.json");
	}

}
