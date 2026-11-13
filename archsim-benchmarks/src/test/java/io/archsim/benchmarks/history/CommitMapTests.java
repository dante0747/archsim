package io.archsim.benchmarks.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * A rewrite's commit map read from its file.
 */
class CommitMapTests {

	private static final String OLD = "a".repeat(40);

	private static final String NEW = "b".repeat(40);

	@Test
	void read_aPairSeparatedBySpaces_mapsTheOldCommitToTheNew(@TempDir Path folder) throws IOException {
		CommitMap map = CommitMap.read(write(folder, "old                new\n" + OLD + " " + NEW + "\n"));

		assertThat(map.current(OLD)).hasValue(NEW);
	}

	@Test
	void read_aPairSeparatedByATab_mapsTheOldCommitToTheNew(@TempDir Path folder) throws IOException {
		CommitMap map = CommitMap.read(write(folder, "recorded\tcurrent\n" + OLD + "\t" + NEW + "\n"));

		assertThat(map.current(OLD)).hasValue(NEW);
	}

	// a map without its header would lose its first commit
	@Test
	void read_aFileWithoutAHeader_isRefused(@TempDir Path folder) throws IOException {
		Path file = write(folder, OLD + " " + NEW + "\n");

		assertThatIllegalArgumentException().isThrownBy(() -> CommitMap.read(file))
			.withMessageContaining("the first line is not a header");
	}

	// git-filter-repo maps a commit it pruned to the all-zero ID
	@Test
	void current_aPrunedCommit_isEmpty(@TempDir Path folder) throws IOException {
		CommitMap map = CommitMap.read(write(folder, "old new\n" + OLD + " " + "0".repeat(40) + "\n"));

		assertThat(map.current(OLD)).isEmpty();
	}

	@Test
	void current_aCommitTheMapDoesNotName_isEmpty(@TempDir Path folder) throws IOException {
		CommitMap map = CommitMap.read(write(folder, "old new\n"));

		assertThat(map.current(OLD)).isEmpty();
	}

	private static Path write(Path folder, String text) throws IOException {
		Path file = folder.resolve("commit-map.tsv");
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

}
