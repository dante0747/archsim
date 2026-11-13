package io.archsim.benchmarks.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Today's engine trees, derived through small histories before and after a rewrite, and
 * their file.
 */
class TreeIdsTests {

	private static final String ENGINE = TreeIds.ENGINE_FOLDER + "/engine.py";

	private static final String LICENSED = "# licence\nrun()\n";

	@Test
	void derive_aTreeTheRewriteChanged_isTodaysTree(@TempDir Path folder) throws IOException {
		GitFixture before = GitFixture.init(folder.resolve("before"));
		GitFixture now = GitFixture.init(folder.resolve("now"));
		String old = before.commit(Map.of(ENGINE, "run()\n"));
		String rewritten = now.commit(Map.of(ENGINE, LICENSED));

		TreeIds trees = TreeIds.derive(List.of(engineTree(before, old)),
				rewrite(folder, before, now, old + " " + rewritten));

		assertThat(trees.current(engineTree(before, old))).isEqualTo(engineTree(now, rewritten));
	}

	// most of the real history's early commits predate packages/engine
	@Test
	void derive_aCommitWithoutTheEngineFolder_isLeftOut(@TempDir Path folder) throws IOException {
		GitFixture before = GitFixture.init(folder.resolve("before"));
		GitFixture now = GitFixture.init(folder.resolve("now"));
		String readme = before.commit(Map.of("README.md", "archsim\n"));
		String old = before.commit(Map.of(ENGINE, "run()\n"));
		String readmeNow = now.commit(Map.of("README.md", "archsim\n"));
		String rewritten = now.commit(Map.of(ENGINE, LICENSED));
		TreeIds.Rewrite rewrite = rewrite(folder, before, now, readme + " " + readmeNow + "\n" + old + " " + rewritten);

		TreeIds trees = TreeIds.derive(List.of(engineTree(before, old)), rewrite);

		assertThat(trees.current(engineTree(before, old))).isEqualTo(engineTree(now, rewritten));
	}

	@Test
	void derive_aCommitTheRewriteDidNotKeep_isLeftOut(@TempDir Path folder) throws IOException {
		GitFixture before = GitFixture.init(folder.resolve("before"));
		GitFixture now = GitFixture.init(folder.resolve("now"));
		String dropped = before.commit(Map.of(ENGINE, "run()\n", "README.md", "one\n"));
		String kept = before.commit(Map.of("README.md", "two\n"));
		String rewritten = now.commit(Map.of(ENGINE, LICENSED));
		TreeIds.Rewrite rewrite = rewrite(folder, before, now, kept + " " + rewritten);

		TreeIds trees = TreeIds.derive(List.of(engineTree(before, dropped)), rewrite);

		assertThat(trees.current(engineTree(before, dropped))).isEqualTo(engineTree(now, rewritten));
	}

	@Test
	void derive_aTreeNoCommitHad_isRefused(@TempDir Path folder) throws IOException {
		GitFixture before = GitFixture.init(folder.resolve("before"));
		GitFixture now = GitFixture.init(folder.resolve("now"));
		String old = before.commit(Map.of(ENGINE, "run()\n"));
		String rewritten = now.commit(Map.of(ENGINE, LICENSED));
		TreeIds.Rewrite rewrite = rewrite(folder, before, now, old + " " + rewritten);

		assertThatIllegalStateException().isThrownBy(() -> TreeIds.derive(List.of("0".repeat(40)), rewrite))
			.withMessageContaining("no commit before the rewrite had engine tree");
	}

	@Test
	void derive_aTreeWhoseCommitsTheRewriteAllDropped_isRefused(@TempDir Path folder) throws IOException {
		GitFixture before = GitFixture.init(folder.resolve("before"));
		GitFixture now = GitFixture.init(folder.resolve("now"));
		String dropped = before.commit(Map.of(ENGINE, "run()\n"));
		String readme = now.commit(Map.of("README.md", "archsim\n"));
		TreeIds.Rewrite rewrite = rewrite(folder, before, now, "f".repeat(40) + " " + readme);
		List<String> recorded = List.of(engineTree(before, dropped));

		assertThatIllegalStateException().isThrownBy(() -> TreeIds.derive(recorded, rewrite))
			.withMessageContaining("none of the 1 commit that had engine tree")
			.withMessageEndingWith("kept an engine folder through the rewrite");
	}

	// the same engine folder before the rewrite must stay one folder after it
	@Test
	void derive_commitsThatCameOutWithDifferentTrees_isRefused(@TempDir Path folder) throws IOException {
		GitFixture before = GitFixture.init(folder.resolve("before"));
		GitFixture now = GitFixture.init(folder.resolve("now"));
		String first = before.commit(Map.of(ENGINE, "run()\n", "README.md", "one\n"));
		String second = before.commit(Map.of("README.md", "two\n"));
		String firstNow = now.commit(Map.of(ENGINE, LICENSED));
		String secondNow = now.commit(Map.of(ENGINE, "# another licence\nrun()\n"));
		TreeIds.Rewrite rewrite = rewrite(folder, before, now,
				first + " " + firstNow + "\n" + second + " " + secondNow);
		List<String> recorded = List.of(engineTree(before, first));

		assertThatIllegalStateException().isThrownBy(() -> TreeIds.derive(recorded, rewrite))
			.withMessageContaining("came out of the rewrite with 2 trees, not one");
	}

	@Test
	void read_theTextAMapWrites_isTheSameMap(@TempDir Path folder) throws IOException {
		Path file = write(folder, "recorded\tcurrent\n" + "a".repeat(40) + "\t" + "b".repeat(40) + "\n");

		TreeIds trees = TreeIds.read(file);

		assertThat(trees.text()).isEqualTo(Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	void read_aLineWithoutATab_isRefusedByItsNumber(@TempDir Path folder) throws IOException {
		Path file = write(folder, "recorded\tcurrent\n" + "a".repeat(40) + " " + "b".repeat(40) + "\n");

		assertThatIllegalArgumentException().isThrownBy(() -> TreeIds.read(file))
			.withMessageContaining("tree-ids.tsv:2: not two trees");
	}

	@Test
	void read_aFileWithoutItsHeader_isRefused(@TempDir Path folder) throws IOException {
		Path file = write(folder, "a".repeat(40) + "\t" + "b".repeat(40) + "\n");

		assertThatIllegalArgumentException().isThrownBy(() -> TreeIds.read(file))
			.withMessageContaining("the first line is not a header such as 'recorded\tcurrent'");
	}

	@Test
	void current_aTreeTheMapDoesNotName_isItself(@TempDir Path folder) throws IOException {
		Path file = write(folder, "recorded\tcurrent\n");

		String current = TreeIds.read(file).current("c".repeat(40));

		assertThat(current).isEqualTo("c".repeat(40));
	}

	private static String engineTree(GitFixture repository, String commit) throws IOException {
		return repository.tree(commit, TreeIds.ENGINE_FOLDER);
	}

	private static Path write(Path folder, String text) throws IOException {
		Path file = folder.resolve("tree-ids.tsv");
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

	private static TreeIds.Rewrite rewrite(Path folder, GitFixture before, GitFixture now, String pairs)
			throws IOException {
		Path map = folder.resolve("commit-map.tsv");
		Files.writeString(map, "old new\n" + pairs + "\n", StandardCharsets.UTF_8);
		return new TreeIds.Rewrite(new GitRepository(before.directory()), CommitMap.read(map),
				new GitRepository(now.directory()));
	}

}
