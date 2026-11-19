package io.archsim.benchmarks;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.engine.version.EngineVersions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code baselines} in a repository of its own: cache-ttl's first two kept runs, which no
 * later campaign changes, and the stacks' cpusets.
 */
class BaselinesCommandTests {

	private static final RepositoryLayout REPOSITORY = RepositoryLayout.current();

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-13T12:34:56Z"), ZoneOffset.UTC);

	@Test
	void baselines_keptRunsWithoutBaselines_writesEachRunsFileAndSaysSo(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);
		StringWriter out = new StringWriter();

		int exitCode = commandLine(layout, out).execute("baselines");

		assertThat(exitCode).isZero();
		assertThat(out.toString().lines()).endsWith(
				"[12:34:56] wrote benchmarks/results/cache-ttl/run-1/baselines.json",
				"[12:34:56] wrote benchmarks/results/cache-ttl/run-2/baselines.json",
				"[12:34:56] baselines: 2 of 2 kept runs' files written");
	}

	@Test
	void baselines_keptRuns_changesNoRecordedFile(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);
		Map<Path, String> recorded = files(layout.results());

		commandLine(layout, new StringWriter()).execute("baselines");

		assertThat(files(layout.results())).containsAllEntriesOf(recorded).hasSize(recorded.size() + 2);
	}

	@Test
	void baselines_keptRuns_recordsTheEngineTheHarnessAndTheDate(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);

		commandLine(layout, new StringWriter()).execute("baselines");

		JsonNode file = ResultFiles.read(layout.results().resolve("cache-ttl/run-2/baselines.json")).orElseThrow();
		assertThat(file.get("at").stringValue()).isEqualTo("2026-11-13T12:34:56Z");
		assertThat(file.get("engineVersion").stringValue()).isEqualTo(EngineVersions.ENGINE);
		assertThat(file.get("provenance").propertyNames()).containsExactly("commit", "engineSources", "javaBuild",
				"harnessHash", "dirty", "machineId");
	}

	@Test
	void baselines_filesWhoseFiguresAreTodays_writesNothing(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);
		commandLine(layout, new StringWriter()).execute("baselines");
		StringWriter out = new StringWriter();

		commandLine(layout, out).execute("baselines");

		assertThat(out.toString().lines()).last().isEqualTo("[12:34:56] baselines: 0 of 2 kept runs' files written");
	}

	@Test
	void baselines_withRewrite_writesEveryFileAgain(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);
		commandLine(layout, new StringWriter()).execute("baselines");
		StringWriter out = new StringWriter();

		commandLine(layout, out).execute("baselines", "--rewrite");

		assertThat(out.toString().lines()).last().isEqualTo("[12:34:56] baselines: 2 of 2 kept runs' files written");
	}

	private static RepositoryLayout repository(Path root) throws IOException {
		RepositoryLayout layout = new RepositoryLayout(root);
		for (String run : List.of("run-1", "run-2")) {
			ResultCopies.copyWithoutBaselines(REPOSITORY.results().resolve("cache-ttl").resolve(run),
					layout.results().resolve("cache-ttl").resolve(run));
		}
		Files.copy(REPOSITORY.compose(), layout.compose());
		return layout;
	}

	// every file under a folder, with its text
	private static Map<Path, String> files(Path folder) throws IOException {
		Map<Path, String> texts = new LinkedHashMap<>();
		try (Stream<Path> files = Files.walk(folder)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				texts.put(file, Files.readString(file));
			}
		}
		return texts;
	}

	private static CommandLine commandLine(RepositoryLayout layout, StringWriter out) {
		PrintWriter writer = new PrintWriter(out, true);
		return Benchmarks.commandLine(Setups.onThisMachine(layout, new HarnessLog(writer, NOON)))
			.setOut(writer)
			.setErr(writer);
	}

}
