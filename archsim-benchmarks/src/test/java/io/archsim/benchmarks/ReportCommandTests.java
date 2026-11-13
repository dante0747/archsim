package io.archsim.benchmarks;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code report} in a repository of its own: the validation page, cache-aside's latest
 * run, the engines and the stacks' cpusets.
 */
class ReportCommandTests {

	private static final RepositoryLayout REPOSITORY = RepositoryLayout.current();

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);

	// only cache-aside's results are there: the other experiments' sections say so
	@Test
	void report_withoutJson_rewritesThePage(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);

		int exitCode = commandLine(layout).execute("report");

		assertThat(exitCode).isZero();
		assertThat(layout.validationPage()).content(StandardCharsets.UTF_8)
			.contains("_No results for pg-mixed yet: run `uv run python benchmarks/run.py`._");
	}

	@Test
	void report_withoutJson_writesTheCharts(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);

		commandLine(layout).execute("report");

		assertThat(layout.validation().resolve("cache-aside.svg")).exists();
	}

	@Test
	void report_withoutJson_writesNoAccuracyJson(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);

		commandLine(layout).execute("report");

		assertThat(List.of(layout.validation().resolve("accuracy.json"), layout.webAccuracy()))
			.allSatisfy((file) -> assertThat(file).doesNotExist());
	}

	// the web image is built from apps/web alone, so the page reads a copy
	@Test
	void report_withJson_writesAccuracyJsonAndTheWebAppsCopy(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);

		commandLine(layout).execute("report", "--json");

		assertThat(Files.readString(layout.webAccuracy(), StandardCharsets.UTF_8))
			.isEqualTo(Files.readString(layout.validation().resolve("accuracy.json"), StandardCharsets.UTF_8))
			.contains("\"id\": \"cache-aside\"");
	}

	@Test
	void report_withDev_writesTheDevelopmentChecks(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);

		commandLine(layout).execute("report", "--dev");

		assertThat(layout.validation().resolve("dev-checks.md")).content(StandardCharsets.UTF_8)
			.contains("_No development checks yet._");
	}

	@Test
	void report_withDev_writesTheKneeRangesPage(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);

		commandLine(layout).execute("report", "--dev");

		assertThat(layout.validation().resolve("knee-range.md")).content(StandardCharsets.UTF_8)
			.contains("_No fit yet._");
	}

	private static RepositoryLayout repository(Path root) throws IOException {
		RepositoryLayout layout = new RepositoryLayout(root);
		ResultCopies.copyLatest(REPOSITORY.results().resolve("cache-aside"), layout.results().resolve("cache-aside"));
		Files.createDirectories(layout.validation());
		Files.copy(REPOSITORY.validationPage(), layout.validationPage());
		Files.copy(REPOSITORY.engines(), layout.engines());
		Files.copy(REPOSITORY.compose(), layout.compose());
		return layout;
	}

	private static CommandLine commandLine(RepositoryLayout layout) {
		PrintWriter out = new PrintWriter(new StringWriter(), true);
		return Benchmarks.commandLine(Setups.onThisMachine(layout, new HarnessLog(out, NOON))).setOut(out).setErr(out);
	}

}
