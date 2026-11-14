package io.archsim.tools.commits;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check as the {@code commit-msg} hook runs it on one message, and as CI runs it on a
 * range of commits.
 */
class CheckCommitsTests {

	private final StringWriter out = new StringWriter();

	private final StringWriter err = new StringWriter();

	@Test
	void run_aConventionalMessageFile_exitsWith0(@TempDir Path folder) throws IOException {
		Path message = Files.writeString(folder.resolve("COMMIT_EDITMSG"), "feat: add a thing\n");

		assertThat(run("--file", message.toString())).isZero();
	}

	@Test
	void run_anotherMessageFile_exitsWith1AndSaysWhy(@TempDir Path folder) throws IOException {
		Path message = Files.writeString(folder.resolve("COMMIT_EDITMSG"), "feat: Add a thing\n");

		assertThat(run("--file", message.toString())).isOne();
		assertThat(this.err.toString().lines()).containsExactly(
				"commit message: start the description in lower case: 'Add a thing'",
				"example: feat(engine): model connection pool queueing");
	}

	@Test
	void run_aRangeOfCommits_checksEachOneAndCountsThem(@TempDir Path folder) throws IOException, InterruptedException {
		Git git = Git.init(folder);
		git.commit("feat: add a thing");
		git.commit("Add another thing");

		int exitCode = CheckCommits.run(List.of("--range", "HEAD~2..HEAD"), folder, writer(this.out), writer(this.err));

		assertThat(exitCode).isOne();
		assertThat(this.out.toString().lines()).containsExactly("2 commit(s) checked, 1 problem(s)");
		assertThat(this.err.toString().lines()).singleElement()
			.asString()
			.matches("[0-9a-f]{10}: the header 'Add another thing' is not .*");
	}

	@Test
	void run_help_printsTheUsageAndWhatTheCheckChecks() {
		assertThat(run("-h")).isZero();
		assertThat(this.out.toString()).startsWith("usage: CheckCommits [-h] (--file FILE | --range RANGE)")
			.contains("--range RANGE  every commit in a git revision range");
	}

	@Test
	void run_neitherAFileNorARange_isRefused() {
		assertThat(run()).isEqualTo(2);
		assertThat(this.err.toString()).contains("one of the arguments --file --range is required");
	}

	@Test
	void run_bothAFileAndARange_isRefused() {
		assertThat(run("--file", "x", "--range", "y")).isEqualTo(2);
		assertThat(this.err.toString()).contains("not allowed with argument");
	}

	private int run(String... arguments) {
		return CheckCommits.run(List.of(arguments), Path.of(""), writer(this.out), writer(this.err));
	}

	private static PrintWriter writer(StringWriter text) {
		return new PrintWriter(text, true);
	}

	/**
	 * A repository a test commits to, as someone of its own.
	 *
	 * @param folder the repository
	 */
	private record Git(Path folder) {

		static Git init(Path folder) throws IOException, InterruptedException {
			Git git = new Git(folder);
			git.run("init", "-q");
			git.commit("chore: start");
			return git;
		}

		void commit(String message) throws IOException, InterruptedException {
			run("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "--allow-empty", "-m",
					message);
		}

		private void run(String... arguments) throws IOException, InterruptedException {
			List<String> command = new ArrayList<>(List.of("git", "-C", this.folder.toString()));
			command.addAll(List.of(arguments));
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (process.waitFor() != 0) {
				throw new IOException("git " + String.join(" ", arguments) + ": " + output);
			}
		}

	}

}
