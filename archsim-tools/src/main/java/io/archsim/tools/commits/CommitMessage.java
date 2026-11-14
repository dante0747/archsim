package io.archsim.tools.commits;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A commit message against Conventional Commits (https://www.conventionalcommits.org).
 *
 * <p>
 * A header is {@code type(scope)!: description}: a known type, an optional lower-case
 * scope, an optional {@code !} for a breaking change, then a description that starts in
 * lower case. It is at most 100 characters. Messages carry no AI co-author trailer: every
 * commit is the author's own. Patterns match as Python's {@code re} matched text: their
 * classes are Unicode's, and whitespace is Python's ({@link PythonStr}).
 */
final class CommitMessage {

	/** The types a header may start with. */
	static final List<String> TYPES = List.of("feat", "fix", "docs", "style", "refactor", "perf", "test", "build", "ci",
			"chore", "revert");

	/** The longest header, in characters. */
	static final int MAX_HEADER = 100;

	private static final Pattern HEADER = Pattern.compile("(?<type>" + String.join("|", TYPES)
			+ ")(?:\\((?<scope>[a-z0-9][a-z0-9-]*)\\))?(?<breaking>!)?: (?<description>" + PythonStr.notSpace() + ".*)",
			Pattern.UNICODE_CHARACTER_CLASS);

	/**
	 * A description may start with a name written with more than one capital (CQRS,
	 * TypeScript).
	 */
	private static final Pattern NAME = Pattern.compile("[A-Z]{2,}|[A-Z][a-z0-9]*[A-Z]");

	private static final Pattern AI_TRAILER = Pattern.compile(
			PythonStr.space() + "*co-authored-by:.*\\b(claude|anthropic|copilot|chatgpt|openai)\\b",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

	/**
	 * Work in progress that git creates and squashes away locally; never acceptable on
	 * main.
	 */
	private static final Pattern AUTOSQUASH = Pattern.compile("(fixup|squash|amend)! ");

	private CommitMessage() {
	}

	/**
	 * What is wrong with a commit's message, as CI checks a branch's commits.
	 * @param message the message, as git keeps it
	 * @return each problem, in words; none when it is fine
	 */
	static List<String> problems(String message) {
		List<String> lines = lines(message);
		if (lines.isEmpty()) {
			return List.of("the message is empty");
		}
		String header = PythonStr.rstrip(lines.getFirst());
		List<String> problems = new ArrayList<>(headerProblems(header));
		if (lines.size() > 1 && !PythonStr.isBlank(lines.get(1))) {
			problems.add("leave a blank line between the header and the body");
		}
		lines.stream()
			.filter((line) -> AI_TRAILER.matcher(line).lookingAt())
			.forEach((line) -> problems
				.add("remove the AI co-author trailer: " + PythonStr.repr(PythonStr.strip(line))));
		return problems;
	}

	/**
	 * What is wrong with a message before it is committed, as the {@code commit-msg} hook
	 * checks it: a commit git squashes away locally ({@code fixup!}, {@code squash!},
	 * {@code amend!}) is fine.
	 * @param message the message, as the hook gets it
	 * @return each problem, in words; none when it is fine
	 */
	static List<String> problemsBeforeSquashing(String message) {
		List<String> lines = lines(message);
		if (!lines.isEmpty() && AUTOSQUASH.matcher(PythonStr.rstrip(lines.getFirst())).lookingAt()) {
			return List.of();
		}
		return problems(message);
	}

	private static List<String> headerProblems(String header) {
		List<String> problems = new ArrayList<>();
		Matcher matched = HEADER.matcher(header);
		if (!matched.matches()) {
			problems.add("the header " + PythonStr.repr(header)
					+ " is not `type(scope): description`, with type one of " + String.join(", ", TYPES));
		}
		else {
			String description = matched.group("description");
			if (Character.isUpperCase(description.codePointAt(0)) && !NAME.matcher(description).lookingAt()) {
				problems.add("start the description in lower case: " + PythonStr.repr(description));
			}
		}
		int length = header.codePointCount(0, header.length());
		if (length > MAX_HEADER) {
			problems.add("the header is " + length + " characters (at most " + MAX_HEADER + ")");
		}
		return problems;
	}

	// the message's lines but git's comments, from its first that is not blank
	private static List<String> lines(String message) {
		List<String> lines = new ArrayList<>(
				PythonStr.splitlines(message).stream().filter((line) -> !line.startsWith("#")).toList());
		while (!lines.isEmpty() && PythonStr.isBlank(lines.getFirst())) {
			lines.removeFirst();
		}
		return lines;
	}

}
