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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import io.archsim.benchmarks.history.GitFixture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code tree-ids} on a small repository and the history before its rewrite.
 */
class TreeIdsCommandTests {

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);

	private static final String FOLDER = "packages/engine";

	private static final String ENGINE = FOLDER + "/engine.py";

	@Test
	void treeIds_aRecordedTree_writesTodaysTreeForIt(@TempDir Path folder) throws IOException {
		GitFixture before = GitFixture.init(folder.resolve("before"));
		String old = before.commit(Map.of(ENGINE, "run()\n"));
		GitFixture now = GitFixture.init(folder.resolve("now"));
		String rewritten = now.commit(Map.of(ENGINE, "# licence\nrun()\n"));
		RepositoryLayout layout = repository(now, before.tree(old, FOLDER));
		Path map = commitMap(folder, old + " " + rewritten);

		commandLine(layout, new StringWriter()).execute("tree-ids", "--before", before.directory().toString(),
				"--commit-map", map.toString());

		assertThat(layout.treeIds()).content(StandardCharsets.UTF_8)
			.isEqualTo("recorded\tcurrent\n" + before.tree(old, FOLDER) + "\t" + now.tree(rewritten, FOLDER) + "\n");
	}

	@Test
	void treeIds_aTreeNoCommitHad_printsWhyAndExitsOne(@TempDir Path folder) throws IOException {
		GitFixture before = GitFixture.init(folder.resolve("before"));
		String old = before.commit(Map.of(ENGINE, "run()\n"));
		GitFixture now = GitFixture.init(folder.resolve("now"));
		String rewritten = now.commit(Map.of(ENGINE, "# licence\nrun()\n"));
		RepositoryLayout layout = repository(now, "0".repeat(40));
		Path map = commitMap(folder, old + " " + rewritten);
		StringWriter err = new StringWriter();

		int exitCode = commandLine(layout, err).execute("tree-ids", "--before", before.directory().toString(),
				"--commit-map", map.toString());

		assertThat(exitCode).isEqualTo(1);
		assertThat(err.toString()).startsWith("no commit before the rewrite had engine tree");
	}

	@Test
	void treeIds_aBeforeThatIsNotARepository_printsGitsReasonAndExitsOne(@TempDir Path folder) throws IOException {
		GitFixture now = GitFixture.init(folder.resolve("now"));
		String rewritten = now.commit(Map.of(ENGINE, "# licence\nrun()\n"));
		RepositoryLayout layout = repository(now, now.tree(rewritten, FOLDER));
		Path notARepository = Files.createDirectories(folder.resolve("before"));
		StringWriter err = new StringWriter();

		int exitCode = commandLine(layout, err).execute("tree-ids", "--before", notARepository.toString(),
				"--commit-map", commitMap(folder, rewritten + " " + rewritten).toString());

		assertThat(exitCode).isEqualTo(1);
		assertThat(err.toString()).startsWith("git -C ").contains("not a git repository");
	}

	@Test
	void treeIds_aCommitMapWithoutAHeader_printsWhyAndExitsOne(@TempDir Path folder) throws IOException {
		GitFixture now = GitFixture.init(folder.resolve("now"));
		String rewritten = now.commit(Map.of(ENGINE, "# licence\nrun()\n"));
		RepositoryLayout layout = repository(now, now.tree(rewritten, FOLDER));
		Path map = Files.writeString(folder.resolve("commit-map.tsv"), rewritten + " " + rewritten + "\n",
				StandardCharsets.UTF_8);
		StringWriter err = new StringWriter();

		int exitCode = commandLine(layout, err).execute("tree-ids", "--before", now.directory().toString(),
				"--commit-map", map.toString());

		assertThat(exitCode).isEqualTo(1);
		assertThat(err.toString()).startsWith(map + ": the first line is not a header such as 'old new'");
	}

	@Test
	void treeIds_aCommitMapThatIsNotThere_printsWhyAndExitsOne(@TempDir Path folder) throws IOException {
		GitFixture now = GitFixture.init(folder.resolve("now"));
		String rewritten = now.commit(Map.of(ENGINE, "# licence\nrun()\n"));
		RepositoryLayout layout = repository(now, now.tree(rewritten, FOLDER));
		Path map = folder.resolve("commit-map.tsv");
		StringWriter err = new StringWriter();

		int exitCode = commandLine(layout, err).execute("tree-ids", "--before", now.directory().toString(),
				"--commit-map", map.toString());

		assertThat(exitCode).isEqualTo(1);
		assertThat(err.toString()).startsWith(map + ": no such file");
	}

	// the repository today, its engines.json recording one engine tree
	private static RepositoryLayout repository(GitFixture now, String recorded) throws IOException {
		RepositoryLayout layout = new RepositoryLayout(now.directory());
		Files.createDirectories(layout.results());
		Files.writeString(layout.engines(), "[{\"version\": \"0.16.0\", \"engineTree\": \"" + recorded + "\"}]",
				StandardCharsets.UTF_8);
		return layout;
	}

	private static Path commitMap(Path folder, String pairs) throws IOException {
		Path map = folder.resolve("commit-map.tsv");
		Files.writeString(map, "old new\n" + pairs + "\n", StandardCharsets.UTF_8);
		return map;
	}

	private static CommandLine commandLine(RepositoryLayout layout, StringWriter err) {
		PrintWriter out = new PrintWriter(new StringWriter(), true);
		return Benchmarks.commandLine(layout, new HarnessLog(out, NOON)).setOut(out).setErr(new PrintWriter(err, true));
	}

}
