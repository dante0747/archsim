package io.archsim.benchmarks;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.archsim.benchmarks.history.GitFixture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code engine-id} on a small repository: it prints what identifies the engine there and
 * what a freeze records, and writes nothing.
 */
class EngineIdCommandTests {

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);

	@Test
	void engineId_aCommittedEngine_printsWhatItsFreezeRecordsAndExitsZero(@TempDir Path folder) throws IOException {
		StringWriter out = new StringWriter();

		int exitCode = engineId(repository(folder), out);

		assertThat(exitCode).isZero();
		assertThat(out.toString()).contains("sources ").contains("to freeze it: tag this commit engine-");
	}

	@Test
	void engineId_aRepository_writesNothing(@TempDir Path folder) throws IOException {
		RepositoryLayout layout = repository(folder);
		Map<Path, String> before = files(folder);

		engineId(layout, new StringWriter());

		assertThat(files(folder)).isEqualTo(before);
	}

	// a folder git knows nothing of: no sources to name, so no freeze either
	@Test
	void engineId_aFolderThatIsNoRepository_namesNoFreeze(@TempDir Path folder) throws IOException {
		Files.createDirectories(folder.resolve("benchmarks"));
		StringWriter out = new StringWriter();

		engineId(new RepositoryLayout(folder), out);

		assertThat(out.toString()).contains("commit unknown").doesNotContain("to freeze it");
	}

	// runs the command, what it prints going to the writer
	private static int engineId(RepositoryLayout layout, StringWriter printed) {
		PrintWriter out = new PrintWriter(printed, true);
		return Benchmarks.commandLine(Setups.onThisMachine(layout, new HarnessLog(out, NOON)))
			.setOut(out)
			.setErr(out)
			.execute("engine-id");
	}

	// a repository holding the engine's three source trees and an engines.json, all
	// committed
	private static RepositoryLayout repository(Path folder) throws IOException {
		GitFixture fixture = GitFixture.init(folder);
		fixture.commit(Map.of("archsim-engine/Engine.java", "class Engine {}\n", "archsim-model/Model.java",
				"class Model {}\n", "archsim-cli/Cli.java", "class Cli {}\n", "benchmarks/engines.json", "[]\n"));
		return new RepositoryLayout(fixture.directory());
	}

	// every file outside .git, with its text
	private static Map<Path, String> files(Path folder) throws IOException {
		Map<Path, String> texts = new TreeMap<>();
		try (Stream<Path> paths = Files.walk(folder)) {
			for (Path path : paths.filter((file) -> !file.toString().contains(".git") && Files.isRegularFile(file))
				.toList()) {
				texts.put(path, Files.readString(path));
			}
		}
		return texts;
	}

}
