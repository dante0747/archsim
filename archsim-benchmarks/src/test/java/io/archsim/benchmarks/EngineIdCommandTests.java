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
import io.archsim.benchmarks.machine.ContentionFit;
import io.archsim.benchmarks.machine.KnownMachine;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code engine-id} on a small repository: it prints what identifies the engine there and
 * what a freeze records, and writes nothing.
 */
class EngineIdCommandTests {

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);

	/** A contention check of the known machine, where {@code contention} writes it. */
	private static final String CHECK = "benchmarks/results/envcheck/2026-11-01T03-00-00Z-contention.json";

	/** The machine the check measured. */
	private static final Map<String, String> ON_THE_CHECKED_MACHINE = Map.of("BENCH_MACHINE", KnownMachine.ID);

	@Test
	void engineId_aCommittedEngine_printsWhatItsFreezeRecordsAndExitsZero(@TempDir Path folder) throws IOException {
		StringWriter out = new StringWriter();

		int exitCode = engineId(repository(folder), out);

		assertThat(exitCode).isZero();
		assertThat(out.toString()).contains("sources ").contains("to freeze it: tag this commit engine-");
	}

	// no contention check of this machine: the designs have no host machine to name
	@Test
	void engineId_noHostMachine_namesNone(@TempDir Path folder) throws IOException {
		StringWriter out = new StringWriter();

		engineId(repository(folder), out, ON_THE_CHECKED_MACHINE);

		assertThat(out.toString()).contains("to freeze it: ").doesNotContain("hostFit");
	}

	@Test
	void engineId_onTheHostMachineOfItsCommittedChecks_namesTheFitsDigest(@TempDir Path folder) throws IOException {
		RepositoryLayout layout = repository(folder, Map.of(CHECK, KnownMachine.check().toString()));
		StringWriter out = new StringWriter();

		engineId(layout, out, ON_THE_CHECKED_MACHINE);

		assertThat(out.toString().lines()).last()
			.asString()
			.endsWith(",\"hostFit\":\"" + ContentionFit.of(KnownMachine.check()).orElseThrow().digest() + "\"}");
	}

	@Test
	void engineId_aWhatIfHostMachine_saysToUnsetItInsteadOfAnEntry(@TempDir Path folder) throws IOException {
		StringWriter out = new StringWriter();

		engineId(repository(folder), out, Map.of("BENCH_MACHINE", KnownMachine.ID, "BENCH_HOST_CLOCK", "0.9"));

		assertThat(out.toString()).contains("BENCH_HOST_CLOCK puts the designs on a what-if host machine")
			.doesNotContain("to freeze it");
	}

	@Test
	void engineId_aContentionCheckNotCommitted_saysToCommitItFirst(@TempDir Path folder) throws IOException {
		RepositoryLayout layout = repository(folder);
		Files.createDirectories(folder.resolve(CHECK).getParent());
		Files.writeString(folder.resolve(CHECK), KnownMachine.check().toString());
		StringWriter out = new StringWriter();

		engineId(layout, out, ON_THE_CHECKED_MACHINE);

		assertThat(out.toString())
			.contains("the checks in benchmarks/results/envcheck have uncommitted changes (or git cannot tell)")
			.doesNotContain("to freeze it");
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
		return engineId(layout, printed, ON_THE_CHECKED_MACHINE);
	}

	private static int engineId(RepositoryLayout layout, StringWriter printed, Map<String, String> environment) {
		PrintWriter out = new PrintWriter(printed, true);
		return Benchmarks.commandLine(Setups.onThisMachine(layout, new HarnessLog(out, NOON), environment))
			.setOut(out)
			.setErr(out)
			.execute("engine-id");
	}

	// a repository holding the engine's three source trees and an engines.json, all
	// committed
	private static RepositoryLayout repository(Path folder) throws IOException {
		return repository(folder, Map.of());
	}

	// the same with more files, committed too
	private static RepositoryLayout repository(Path folder, Map<String, String> more) throws IOException {
		Map<String, String> files = new TreeMap<>(Map.of("archsim-engine/Engine.java", "class Engine {}\n",
				"archsim-model/Model.java", "class Model {}\n", "archsim-cli/Cli.java", "class Cli {}\n",
				"benchmarks/engines.json", "[]\n"));
		files.putAll(more);
		GitFixture fixture = GitFixture.init(folder);
		fixture.commit(files);
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
