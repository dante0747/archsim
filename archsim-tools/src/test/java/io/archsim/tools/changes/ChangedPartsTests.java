package io.archsim.tools.changes;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI's {@code changes} job on a repository a test commits to: a docs file and a text file
 * at the root, then a change to them.
 */
class ChangedPartsTests {

	private final StringWriter out = new StringWriter();

	private final StringWriter err = new StringWriter();

	@Test
	void changedFiles_aRename_listsBothSides(@TempDir Path folder) throws IOException, InterruptedException {
		Git git = Git.start(folder);
		String base = git.output("rev-parse", "HEAD");
		Files.writeString(folder.resolve("docs/a.md"), "b\n");
		git.run("mv", "old.txt", "new.txt");
		git.run("commit", "-q", "-am", "docs: change");

		assertThat(GitDiff.changedFiles(base, git.output("rev-parse", "HEAD"), folder).orElseThrow())
			.containsExactlyInAnyOrder(new ChangedFile("docs/a.md", false), new ChangedFile("new.txt", false),
					new ChangedFile("old.txt", true));
	}

	// none, a new branch's all zeros, or a commit rewritten history left out of the clone
	@ParameterizedTest
	@ValueSource(
			strings = { "", "0000000000000000000000000000000000000000", "1234567890abcdef1234567890abcdef12345678" })
	void changedFiles_noUsableBase_isUnknown(String base, @TempDir Path folder)
			throws IOException, InterruptedException {
		Git git = Git.start(folder);

		assertThat(GitDiff.changedFiles(base, git.output("rev-parse", "HEAD"), folder)).isEmpty();
	}

	@Test
	void run_aDocsOnlyPush_writesOnlyTheWebsiteTrue(@TempDir Path folder, @TempDir Path outputs)
			throws IOException, InterruptedException {
		Git git = Git.start(folder);
		String base = git.output("rev-parse", "HEAD");
		Files.writeString(folder.resolve("docs/a.md"), "b\n");
		git.run("commit", "-q", "-am", "docs: change");
		Path output = outputs.resolve("github_output");

		int exitCode = run(folder, "--base", base, "--head", "HEAD", "--github-output", output.toString());

		assertThat(exitCode).isZero();
		assertThat(Files.readAllLines(output)).containsExactly("java=false", "archsim_check=false", "web=false",
				"e2e=false", "site=true");
	}

	@Test
	void run_aDocsOnlyPush_skipsEveryJobButTheWebsite(@TempDir Path folder) throws IOException, InterruptedException {
		Git git = Git.start(folder);
		String base = git.output("rev-parse", "HEAD");
		Files.writeString(folder.resolve("docs/a.md"), "b\n");
		git.run("commit", "-q", "-am", "docs: change");

		run(folder, "--base", base, "--head", "HEAD");

		assertThat(this.out.toString().lines()).containsExactly("1 changed file(s)", "  java: skipped",
				"  archsim_check: skipped", "  web: skipped", "  e2e: skipped", "  site: runs (docs/a.md)");
	}

	// the docs may link to it, and the website's build fails on a link to a file that is
	// gone
	@Test
	void run_aPushThatDeletesAFile_runsTheWebsite(@TempDir Path folder) throws IOException, InterruptedException {
		Git git = Git.start(folder);
		Files.writeString(folder.resolve("AGENTS.md"), "a\n");
		git.run("add", ".");
		git.run("commit", "-q", "-m", "docs: add the agents' guide");
		String base = git.output("rev-parse", "HEAD");
		git.run("rm", "-q", "AGENTS.md");
		git.run("commit", "-q", "-m", "docs: drop the agents' guide");

		run(folder, "--base", base, "--head", "HEAD");

		assertThat(this.out.toString().lines()).containsExactly("1 changed file(s)", "  java: skipped",
				"  archsim_check: skipped", "  web: skipped", "  e2e: skipped", "  site: runs (deleted: AGENTS.md)");
	}

	@Test
	void run_aChangeAJobNeeds_namesTheFileThatNeedsIt(@TempDir Path folder) throws IOException, InterruptedException {
		Git git = Git.start(folder);
		String base = git.output("rev-parse", "HEAD");
		Files.createDirectories(folder.resolve("apps/web"));
		Files.writeString(folder.resolve("apps/web/page.tsx"), "x\n");
		git.run("add", ".");
		git.run("commit", "-q", "-m", "feat(web): add a page");

		run(folder, "--base", base, "--head", "HEAD");

		assertThat(this.out.toString().lines()).contains("  web: runs (apps/web/page.tsx)", "  java: skipped");
	}

	// a run started by hand, or a push without a base to compare with
	@ParameterizedTest
	@ValueSource(strings = { "--all", "--base=" })
	void run_aRunByHandOrWithoutABase_writesEveryJobTrue(String argument, @TempDir Path folder, @TempDir Path outputs)
			throws IOException, InterruptedException {
		Git.start(folder);
		Path output = outputs.resolve("github_output");
		List<String> arguments = new ArrayList<>(List.of(argument, "--github-output", output.toString()));
		if (!"--all".equals(argument)) {
			arguments.addAll(List.of("--head", "HEAD"));
		}

		run(folder, arguments.toArray(String[]::new));

		assertThat(Files.readAllLines(output)).containsExactly("java=true", "archsim_check=true", "web=true",
				"e2e=true", "site=true");
		assertThat(this.out.toString().lines())
			.containsExactly("every job: a run by hand, or no usable base to compare with");
	}

	@Test
	void run_help_printsTheUsageAndWhatEachOptionDoes(@TempDir Path folder) {
		assertThat(run(folder, "-h")).isZero();
		assertThat(this.out.toString()).startsWith("usage: ChangedParts [-h] [--all] [--base BASE] [--head HEAD]")
			.contains("Which CI jobs a change needs")
			.contains("--github-output GITHUB_OUTPUT\n");
	}

	@Test
	void run_neitherAllNorAHead_isRefused(@TempDir Path folder) {
		assertThat(run(folder, "--base", "abc")).isEqualTo(2);
		assertThat(this.err.toString()).contains("give --all, or --base and --head");
	}

	// as CI's changes job runs it: from its source, the JDK alone on the class path
	@Test
	void launch_fromItsSource_saysEveryJobRuns() throws IOException, InterruptedException {
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		Process process = new ProcessBuilder(java.toString(),
				"src/main/java/io/archsim/tools/changes/ChangedParts.java", "--all")
			.redirectErrorStream(true)
			.start();

		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

		assertThat(process.waitFor()).isZero();
		assertThat(output.lines()).containsExactly("every job: a run by hand, or no usable base to compare with");
	}

	private int run(Path folder, String... arguments) {
		return ChangedParts.run(List.of(arguments), folder, new PrintWriter(this.out, true),
				new PrintWriter(this.err, true));
	}

	/**
	 * A repository a test commits to, as someone of its own.
	 *
	 * @param folder the repository
	 */
	private record Git(Path folder) {

		// docs/a.md and old.txt, committed
		static Git start(Path folder) throws IOException, InterruptedException {
			Git git = new Git(folder);
			git.run("init", "-q");
			Files.createDirectories(folder.resolve("docs"));
			Files.writeString(folder.resolve("docs/a.md"), "a\n");
			Files.writeString(folder.resolve("old.txt"), "old\n");
			git.run("add", ".");
			git.run("commit", "-q", "-m", "docs: start");
			return git;
		}

		void run(String... arguments) throws IOException, InterruptedException {
			output(arguments);
		}

		String output(String... arguments) throws IOException, InterruptedException {
			List<String> command = new ArrayList<>(
					List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com"));
			command.addAll(Stream.of(arguments).toList());
			Process process = new ProcessBuilder(command).directory(this.folder.toFile())
				.redirectErrorStream(true)
				.start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
			if (process.waitFor() != 0) {
				throw new IOException("git " + String.join(" ", arguments) + ": " + output);
			}
			return output;
		}

	}

}
