package io.archsim.tools.commits;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks commit messages against Conventional Commits ({@link CommitMessage}): one
 * message, as the {@code commit-msg} hook passes it, or every commit in a range, as CI
 * checks a branch. It uses the JDK alone, so that Java's source launcher runs it without
 * a build:
 *
 * <pre>
 * java archsim-tools/src/main/java/io/archsim/tools/commits/CheckCommits.java --file .git/COMMIT_EDITMSG
 * java archsim-tools/src/main/java/io/archsim/tools/commits/CheckCommits.java --range origin/main..HEAD
 * </pre>
 */
public final class CheckCommits {

	private static final String USAGE = "usage: CheckCommits [-h] (--file FILE | --range RANGE)";

	private static final String EXAMPLE = "example: feat(engine): model connection pool queueing";

	/** What {@code -h} prints, as argparse printed it. */
	private static final String HELP = USAGE + """


			Check commit messages against Conventional Commits (https://www.conventionalcommits.org).

			A header is `type(scope)!: description`: a known type, an optional lower-case scope, an optional
			`!` for a breaking change, then a description that starts in lower case. It is at most 100
			characters. Messages carry no AI co-author trailer: every commit is the author's own.

			options:
			  -h, --help     show this help message and exit
			  --file FILE    one message, as the commit-msg hook passes it
			  --range RANGE  every commit in a git revision range, e.g. origin/main..HEAD
			""";

	private static final int MISUSED = 2;

	private CheckCommits() {
	}

	/**
	 * Checks the messages the arguments name, and exits with 0 when every one is fine, 1
	 * when one is not, 2 when the arguments are wrong.
	 * @param args {@code --file FILE} or {@code --range RANGE}
	 */
	public static void main(String[] args) {
		PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
		PrintWriter err = new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
		System.exit(run(List.of(args), Path.of(""), out, err));
	}

	/**
	 * Checks the messages the arguments name.
	 * @param arguments {@code --file FILE} or {@code --range RANGE}, or {@code -h}
	 * @param directory the repository a range is in, the empty path for the working
	 * folder
	 * @param out where the range's count goes
	 * @param err where each problem goes
	 * @return 0 when every message is fine, 1 when one is not or cannot be read, 2 when
	 * the arguments are wrong
	 */
	static int run(List<String> arguments, Path directory, PrintWriter out, PrintWriter err) {
		Arguments parsed;
		try {
			parsed = Arguments.of(arguments);
		}
		catch (IllegalArgumentException ex) {
			err.println(USAGE);
			err.println("CheckCommits: error: " + ex.getMessage());
			return MISUSED;
		}
		try {
			return switch (parsed.mode()) {
				case HELP -> help(out);
				case FILE -> file(Path.of(parsed.value()), err);
				case RANGE -> range(parsed.value(), directory, out, err);
			};
		}
		catch (IOException ex) {
			err.println("CheckCommits: " + ex.getMessage());
			return 1;
		}
	}

	private static int help(PrintWriter out) {
		out.print(HELP);
		out.flush();
		return 0;
	}

	// one message, as the hook passes it: a commit git squashes away locally is fine
	private static int file(Path file, PrintWriter err) throws IOException {
		List<String> problems = CommitMessage.problemsBeforeSquashing(Files.readString(file, StandardCharsets.UTF_8));
		problems.forEach((problem) -> err.println("commit message: " + problem));
		if (problems.isEmpty()) {
			return 0;
		}
		err.println(EXAMPLE);
		return 1;
	}

	// every commit in a range, as CI checks a branch
	private static int range(String range, Path directory, PrintWriter out, PrintWriter err) throws IOException {
		List<GitLog.Commit> commits = GitLog.commits(range, directory);
		int problems = 0;
		for (GitLog.Commit commit : commits) {
			for (String problem : CommitMessage.problems(commit.message())) {
				err.println(commit.shortSha() + ": " + problem);
				problems++;
			}
		}
		out.println(commits.size() + " commit(s) checked, " + problems + " problem(s)");
		return (problems > 0) ? 1 : 0;
	}

}
