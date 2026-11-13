package io.archsim.benchmarks.history;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * A repository read with {@code git}: a history's worth of revisions at once, and git's
 * refusals.
 */
class GitRepositoryTests {

	private static final String FOLDER = "packages/engine";

	@Test
	void commits_aFolderThatIsNotARepository_isRefusedWithWhatGitSaid(@TempDir Path folder) {
		GitRepository repository = new GitRepository(folder);

		assertThatIllegalStateException().isThrownBy(repository::commits).withMessageContaining("not a git repository");
	}

	// about 285 KB of input, more than a pipe holds on Windows or Linux: through pipes,
	// writing it all before reading git's answers would block for good, and a blocked
	// write ignores interrupts, so the timeout runs the test in a thread of its own
	@Test
	@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = ThreadMode.SEPARATE_THREAD)
	void folderTrees_moreInputThanAPipeHolds_answersWithoutBlocking(@TempDir Path folder) throws IOException {
		GitFixture fixture = GitFixture.init(folder);
		String commit = fixture.commit(Map.of(FOLDER + "/engine.py", "run()\n"));
		List<String> revisions = Collections.nCopies(5_000, commit);

		Map<String, String> trees = new GitRepository(folder).folderTrees(revisions, FOLDER);

		assertThat(trees).isEqualTo(Map.of(commit, fixture.tree(commit, FOLDER)));
	}

}
