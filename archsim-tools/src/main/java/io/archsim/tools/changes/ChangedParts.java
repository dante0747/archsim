package io.archsim.tools.changes;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which CI jobs a change needs ({@link Rules}). The repository is private, so every CI
 * minute is billed: CI's {@code changes} job runs this on the files a pull request or a
 * push changes, and the jobs none of them can affect are skipped. A change to CI itself,
 * a run started by hand and a push without a usable base (a new branch, or history that
 * was rewritten) run every job. {@code Commit messages} always runs and is not decided
 * here. It uses the JDK alone, so that the job runs it from source:
 *
 * <pre>
 * java archsim-tools/src/main/java/io/archsim/tools/changes/ChangedParts.java --base BASE --head HEAD
 * java archsim-tools/src/main/java/io/archsim/tools/changes/ChangedParts.java --all --github-output "$GITHUB_OUTPUT"
 * </pre>
 */
public final class ChangedParts {

	private static final String USAGE = "usage: ChangedParts [-h] [--all] [--base BASE] [--head HEAD] "
			+ "[--github-output GITHUB_OUTPUT]";

	/** What {@code -h} prints, as argparse printed it. */
	private static final String HELP = USAGE + """


			Which CI jobs a change needs: the jobs that one of its changed files can affect.

			The repository is private, so every CI minute is billed. CI's `changes` job runs this on the
			files a pull request or a push changes, and the jobs none of them can affect are skipped. A path
			no rule names runs every job, so a new folder is tested until it has a rule. So do a change to
			CI itself, a run started by hand, and a push without a usable base (a new branch, or history
			that was rewritten). `Commit messages` always runs and is not decided here.

			When a test starts reading a file outside its own part of the repository (the Java tests read
			docs/assumptions.md, parity/fixtures and examples/), add that file or its folder to
			Rules.STANDARD, or a change to it alone will not run the test.

			    java archsim-tools/src/main/java/io/archsim/tools/changes/ChangedParts.java --base BASE --head HEAD
			    java archsim-tools/src/main/java/io/archsim/tools/changes/ChangedParts.java --all --github-output FILE

			options:
			  -h, --help            show this help message and exit
			  --all                 every job (a run started by hand)
			  --base BASE           the base: the pull request's base, or the push's `before`
			  --head HEAD           the head: the pull request's head, or the pushed commit
			  --github-output GITHUB_OUTPUT
			                        append job=true|false lines to this file
			""";

	private static final int MISUSED = 2;

	private ChangedParts() {
	}

	/**
	 * Says which jobs the change needs, and exits with 0, or with 2 when the arguments
	 * are wrong.
	 * @param args {@code --all}, or {@code --base BASE --head HEAD}, and
	 * {@code --github-output FILE}
	 */
	public static void main(String[] args) {
		PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
		PrintWriter err = new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
		System.exit(run(List.of(args), Path.of(""), out, err));
	}

	/**
	 * Says which jobs the change needs, each with the first file that needs it, and
	 * appends {@code job=true|false} lines to GitHub's output file when one is given.
	 * @param arguments the command line's arguments
	 * @param directory the repository, the empty path for the working folder
	 * @param out where the jobs go
	 * @param err where an error goes
	 * @return 0, or 1 when the output file cannot be written, 2 when the arguments are
	 * wrong
	 */
	static int run(List<String> arguments, Path directory, PrintWriter out, PrintWriter err) {
		Arguments parsed;
		try {
			parsed = Arguments.of(arguments);
		}
		catch (IllegalArgumentException ex) {
			err.println(USAGE);
			err.println("ChangedParts: error: " + ex.getMessage());
			return MISUSED;
		}
		if (parsed.help()) {
			out.print(HELP);
			out.flush();
			return 0;
		}
		Optional<List<ChangedFile>> files = parsed.all() ? Optional.empty()
				: GitDiff.changedFiles(parsed.base(), parsed.head().orElseThrow(), directory);
		Set<Job> needed = files.map(Rules::jobs).orElse(Rules.EVERY);
		files.ifPresentOrElse((changed) -> report(changed, out),
				() -> out.println("every job: a run by hand, or no usable base to compare with"));
		return parsed.githubOutput().map((output) -> write(output, needed, err)).orElse(0);
	}

	// GitHub's step outputs appended to its file: each job, whether it runs
	private static int write(Path output, Set<Job> needed, PrintWriter err) {
		try {
			Files.writeString(output, outputs(needed), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
			return 0;
		}
		catch (IOException ex) {
			err.println("ChangedParts: " + ex.getMessage());
			return 1;
		}
	}

	// each job, and the first file that needs it
	private static void report(List<ChangedFile> files, PrintWriter out) {
		out.println(files.size() + " changed file(s)");
		for (Job job : Job.values()) {
			Optional<ChangedFile> why = files.stream().filter((file) -> Rules.neededBy(file).contains(job)).findFirst();
			out.println("  " + job.id() + ": " + why.map((file) -> "runs (" + file.label() + ")").orElse("skipped"));
		}
	}

	// GitHub's step outputs: each job, whether it runs
	private static String outputs(Set<Job> needed) {
		return List.of(Job.values())
			.stream()
			.map((job) -> job.id() + "=" + needed.contains(job) + "\n")
			.collect(Collectors.joining());
	}

}
