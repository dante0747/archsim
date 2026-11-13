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
import picocli.CommandLine;

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
	void execute_withoutACommand_isRefused() {
		int exitCode = commandLine(Path.of("."), new StringWriter()).execute();

		assertThat(exitCode).isEqualTo(2);
	}

	private static CommandLine commandLine(Path root, StringWriter out) {
		PrintWriter writer = new PrintWriter(out, true);
		return Benchmarks.commandLine(new RepositoryLayout(root), new HarnessLog(writer, NOON))
			.setOut(writer)
			.setErr(writer);
	}

}
