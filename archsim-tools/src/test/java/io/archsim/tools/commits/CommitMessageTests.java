package io.archsim.tools.commits;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A commit message against Conventional Commits: a known type, an optional lower-case
 * scope, an optional {@code !}, then a description that starts in lower case, at most 100
 * characters, and no AI co-author trailer.
 */
class CommitMessageTests {

	@ParameterizedTest
	@ValueSource(strings = { "feat: add Kafka fetch cycles", "fix(engine): count failures in p95",
			"feat(api)!: drop the v1 share links", "build(deps-dev): bump eslint from 9.1 to 9.2",
			"docs(readme): retake the screenshots\n\nThe old ones showed the previous theme.\n",
			"feat(templates): add CQRS and event sourcing",
			"chore(benchmarks): add the v6 campaign\n\nBREAKING CHANGE: protocol v6 replaces v5\n",
			"refactor: move TypeScript helpers\n# a comment git strips\n" })
	void problems_aConventionalMessage_isNone(String message) {
		assertThat(CommitMessage.problems(message)).isEmpty();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
			Add circuit breaker component | is not `type(scope): description`
			feature: add a thing | is not `type(scope): description`
			feat(Engine): add a thing | is not `type(scope): description`
			feat:add a thing | is not `type(scope): description`
			feat: Add a thing | start the description in lower case
			feat: add a thing\\nno blank line | blank line
			feat: add a thing\\n\\nCo-Authored-By: Claude <noreply@anthropic.com> | AI co-author
			Merge branch 'w23' into main | is not `type(scope): description`
			"" | empty
			""")
	void problems_anotherMessage_saysWhatIsWrong(String message, String fragment) {
		assertThat(CommitMessage.problems(message.replace("\\n", "\n")))
			.anyMatch((problem) -> problem.contains(fragment));
	}

	@Test
	void problems_aHeaderOver100Characters_saysHowLong() {
		assertThat(CommitMessage.problems("feat: " + "x".repeat(95)))
			.contains("the header is 101 characters (at most 100)");
	}

	@Test
	void problems_aHeaderThatIsNotConventional_quotesItAsPythonDid() {
		assertThat(CommitMessage.problems("Merge branch 'w23' into main")).containsExactly(
				"the header \"Merge branch 'w23' into main\" is not `type(scope): description`, with type one of "
						+ "feat, fix, docs, style, refactor, perf, test, build, ci, chore, revert");
	}

	// Python's whitespace, not Java's: run.py trimmed a trailing no-break space
	@Test
	void problems_aHeaderEndingInANoBreakSpace_isTrimmedAsPythonDid() {
		assertThat(CommitMessage.problems("feat: " + "a".repeat(94) + "\u00a0\n")).isEmpty();
	}

	@Test
	void problems_aFirstLineOfANoBreakSpace_isBlank() {
		assertThat(CommitMessage.problems("\u00a0\nfeat: add the thing\n")).isEmpty();
	}

	@Test
	void problems_aSecondLineOfAFigureSpace_isBlank() {
		assertThat(CommitMessage.problems("feat: add the thing\n\u2007\nbody\n")).isEmpty();
	}

	// an information separator is whitespace to Python's re too
	@Test
	void problems_aDescriptionStartingWithASeparator_isNotConventional() {
		assertThat(CommitMessage.problems("feat: \u001cadd it\n")).isNotEmpty();
	}

	@Test
	void problems_aHumanCoAuthor_isNone() {
		assertThat(CommitMessage.problems("fix: a typo\n\nCo-Authored-By: Sam <sam@example.com>")).isEmpty();
	}

	// work in progress git squashes away locally; never acceptable on main
	@Test
	void problemsBeforeSquashing_anAutosquashCommit_isNone() {
		assertThat(CommitMessage.problemsBeforeSquashing("fixup! feat: add a thing")).isEmpty();
	}

	@Test
	void problems_anAutosquashCommit_isNotConventional() {
		assertThat(CommitMessage.problems("fixup! feat: add a thing")).isNotEmpty();
	}

}
