package io.archsim.tools.changes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which CI jobs a change needs: the jobs that read its files, every job for CI itself,
 * the templates and a path no rule names, none for what only people read.
 */
class RulesTests {

	private static final Path ROOT = Path.of(System.getProperty("archsim.repoRoot", ".."));

	@ParameterizedTest
	@CsvFileSource(resources = "changes.csv", delimiter = '|')
	void jobs_aChange_runsTheJobsThatReadItsFiles(String files, String jobs) {
		List<ChangedFile> changed = words(files).stream().map((path) -> new ChangedFile(path, false)).toList();

		assertThat(ids(Rules.jobs(changed))).isEqualTo(Set.copyOf(words(jobs)));
	}

	// the website's build fails on a link to a file that no longer exists, wherever it is
	@Test
	void neededBy_aDeletedFileNoJobReads_runsTheWebsite() {
		assertThat(Rules.neededBy(new ChangedFile("CONTRIBUTING.md", true))).containsExactly(Job.SITE);
	}

	@Test
	void neededBy_aDeletedFileAJobReads_runsThatJobAndTheWebsite() {
		ChangedFile deleted = new ChangedFile("archsim-mcp/src/main/java/io/archsim/mcp/McpTools.java", true);

		assertThat(Rules.neededBy(deleted)).containsExactlyInAnyOrder(Job.JAVA, Job.SITE);
	}

	@Test
	void jobs_noFiles_isNone() {
		assertThat(Rules.jobs(List.of())).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(
			strings = { ".github/workflows/ci.yml", "archsim-tools/src/main/java/io/archsim/tools/changes/Rules.java",
					"examples/cache-aside-starter.json", "docker-compose.yml", "a-folder-no-rule-names/file.txt" })
	void jobs_ciItselfTheExamplesOrAPathNoRuleNames_isEveryJob(String path) {
		assertThat(Rules.jobs(List.of(new ChangedFile(path, false)))).isEqualTo(Rules.EVERY);
	}

	@Test
	void standard_everyPathARuleNames_exists() throws IOException {
		List<String> missing = new ArrayList<>();
		for (Rule rule : Rules.STANDARD) {
			if (!exists(rule.path())) {
				missing.add(rule.path());
			}
		}
		assertThat(missing).isEmpty();
	}

	@Test
	void standard_noRule_isHiddenBehindAnEarlierOne() {
		List<Rule> rules = Rules.STANDARD;

		assertThat(IntStream.range(0, rules.size())
			.boxed()
			.flatMap((later) -> rules.subList(0, later)
				.stream()
				.filter((earlier) -> earlier.covers(rules.get(later).path()))
				.map((earlier) -> earlier.path() + " hides " + rules.get(later).path())))
			.isEmpty();
	}

	// each job a `changes` output gates is one this declares, and one ci.yml reads
	@Test
	void standard_eachJob_isGatedOnItsOutputInCi() throws IOException {
		String workflow = Files.readString(ROOT.resolve(".github/workflows/ci.yml"));
		Set<String> gated = matches(Pattern.compile("needs\\.changes\\.outputs\\.(\\w+) == 'true'"), workflow, 1);
		Set<String> declared = matches(
				Pattern.compile("(?m)^\\s+(\\w+): \\$\\{\\{ steps\\.jobs\\.outputs\\.(\\w+) \\}\\}"), workflow, 2);

		assertThat(gated).isEqualTo(ids(Rules.EVERY));
		assertThat(declared).isEqualTo(ids(Rules.EVERY));
	}

	// the words of a column, none for an empty one
	private static List<String> words(String column) {
		return (column == null || column.isBlank()) ? List.of() : List.of(column.strip().split(" +"));
	}

	private static Set<String> ids(Set<Job> jobs) {
		return jobs.stream().map(Job::id).collect(Collectors.toSet());
	}

	private static Set<String> matches(Pattern pattern, String text, int group) {
		Matcher matcher = pattern.matcher(text);
		return matcher.results().map((match) -> match.group(group)).collect(Collectors.toSet());
	}

	// a rule's path names a file or a folder, or its pattern some path in the repository
	private static boolean exists(String path) throws IOException {
		if (!path.contains("*")) {
			return Files.exists(ROOT.resolve(path.endsWith("/") ? path.substring(0, path.length() - 1) : path));
		}
		Rule rule = new Rule(path, Set.of());
		Path folder = ROOT.resolve(path.substring(0, path.lastIndexOf('/')));
		try (Stream<Path> files = Files.list(folder)) {
			return files.map((file) -> ROOT.relativize(file).toString().replace('\\', '/')).anyMatch(rule::covers);
		}
	}

}
