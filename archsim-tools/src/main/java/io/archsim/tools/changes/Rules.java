package io.archsim.tools.changes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The paths CI knows and the jobs a change to each needs. The first rule that covers a
 * changed file decides which jobs it needs; a path no rule names runs every job, so a new
 * folder is tested until it has a rule. When a test starts reading a file outside its own
 * part of the repository (the Java tests read {@code docs/assumptions.md},
 * {@code parity/fixtures} and {@code examples/}), add that file or its folder here, or a
 * change to it alone will not run the test.
 */
final class Rules {

	/** Every job: CI itself, the templates, a path no rule names. */
	static final Set<Job> EVERY = Set.copyOf(EnumSet.allOf(Job.class));

	private static final Set<Job> NONE = Set.of();

	private static final Set<Job> PYTHON = Set.of(Job.PYTHON);

	private static final Set<Job> BENCHMARK_TESTS = Set.of(Job.PYTHON, Job.BENCHMARK_TESTS);

	private static final Set<Job> JAVA = Set.of(Job.JAVA, Job.E2E);

	private static final Set<Job> JAVA_ONLY = Set.of(Job.JAVA);

	/**
	 * The archsim-check action builds the Java CLI into its image from these modules and
	 * the Gradle build (ADR-0060), so its job checks a change to any of them.
	 */
	private static final Set<Job> CHECK_ACTION = Set.of(Job.ARCHSIM_CHECK);

	/**
	 * The Python environment, which the Python job syncs: its tests and the benchmarks'
	 * harness.
	 */
	private static final Set<Job> PYTHON_RUNTIME = BENCHMARK_TESTS;

	/**
	 * The web app's engine fixtures: archsim-engine's GeneratedFileTests checks them
	 * against the engine's output, and the web app's tests read them.
	 */
	private static final Set<Job> WEB_FIXTURES = Set.of(Job.WEB, Job.JAVA, Job.E2E);

	/** The rules, in the order they are tried. */
	static final List<Rule> STANDARD = new Table()
		// CI itself
		.rule(".github/workflows/ci.yml", EVERY)
		.rule("archsim-tools/src/main/java/io/archsim/tools/changes/", EVERY)
		// archsim-tools' ArchsimCheckActionTests runs its check step, and
		// the archsim-check job tests its comment and runs it
		.rule(".github/actions/", Set.of(Job.JAVA, Job.ARCHSIM_CHECK))
		// benchmarks/tests/test_held_out.py reads its cpuset loop
		.rule(".github/workflows/benchmark.yml", BENCHMARK_TESTS)
		.rule(".github/", NONE)
		// The web app. The Python benchmark tests and the Java harness's
		// ReportParityTests compare the accuracy page's copy with
		// docs/validation/. archsim-engine's GeneratedFileTests checks the
		// fixtures `./gradlew :archsim-engine:generate` writes from the
		// starter; archsim-model's DesignDocumentsTests reads the starter too.
		.rule("apps/web/src/data/accuracy.json", union(BENCHMARK_TESTS, Set.of(Job.WEB, Job.E2E, Job.JAVA)))
		.rule("apps/web/src/test/fixtures/starter.json", WEB_FIXTURES)
		.rule("apps/web/src/test/fixtures/catalog.json", WEB_FIXTURES)
		.rule("apps/web/src/test/fixtures/analysis-starter.json", WEB_FIXTURES)
		// the end-to-end launcher: the Java tests check the worker's
		// command line in it
		.rule("apps/web/e2e/api-server.mjs", Set.of(Job.WEB, Job.JAVA, Job.E2E))
		.rule("apps/web/", Set.of(Job.WEB, Job.E2E))
		// The Java build. The black-box API tests, the command line, the MCP
		// server and the tooling are not part of the app the end-to-end
		// tests run.
		.rule("archsim-api-tests/", JAVA_ONLY)
		// The benchmarks' tests simulate on the installed Java CLI and ask it
		// for the engine's functions (ADR-0061, ADR-0062): a change to what
		// the CLI is built from (its, the engine's and the model's main
		// sources and build files, and the Gradle build) runs them; the
		// modules' tests, test fixtures and READMEs do not.
		.rule("archsim-cli/src/main/", union(JAVA_ONLY, CHECK_ACTION, BENCHMARK_TESTS))
		.rule("archsim-cli/build.gradle.kts", union(JAVA_ONLY, CHECK_ACTION, BENCHMARK_TESTS))
		.rule("archsim-cli/", union(JAVA_ONLY, CHECK_ACTION))
		.rule("archsim-mcp/", JAVA_ONLY)
		// the harness, whose tests read the kept results under
		// benchmarks/results/ (ADR-0068)
		.rule("archsim-benchmarks/", JAVA_ONLY)
		// the tooling, whose tests the Java build runs
		.rule("archsim-tools/", JAVA_ONLY)
		.rule("archsim-app/", JAVA)
		.rule("archsim-engine/src/main/", union(JAVA, CHECK_ACTION, BENCHMARK_TESTS))
		.rule("archsim-engine/build.gradle.kts", union(JAVA, CHECK_ACTION, BENCHMARK_TESTS))
		.rule("archsim-engine/", union(JAVA, CHECK_ACTION))
		.rule("archsim-model/src/main/", union(JAVA, CHECK_ACTION, BENCHMARK_TESTS))
		.rule("archsim-model/build.gradle.kts", union(JAVA, CHECK_ACTION, BENCHMARK_TESTS))
		.rule("archsim-model/", union(JAVA, CHECK_ACTION))
		.rule("build-logic/", union(JAVA, CHECK_ACTION, BENCHMARK_TESTS))
		.rule("config/", JAVA)
		.rule("gradle/", union(JAVA, CHECK_ACTION, BENCHMARK_TESTS))
		.rule("gradle.properties", union(JAVA, CHECK_ACTION, BENCHMARK_TESTS))
		.rule("gradlew", union(JAVA, CHECK_ACTION, BENCHMARK_TESTS))
		.rule("gradlew.bat", JAVA)
		.rule("settings.gradle.kts", union(JAVA, CHECK_ACTION, BENCHMARK_TESTS))
		// Python
		.rule("pyproject.toml", PYTHON_RUNTIME)
		.rule("uv.lock", PYTHON_RUNTIME)
		.rule("scripts/", PYTHON)
		// the engines' speed (the frozen engine's, and the Java speed
		// task's): the Java harness's parity page test reads two of these
		// files, and nothing reads the earlier measurements
		.rule("benchmarks/perf/java-port.json", JAVA_ONLY)
		.rule("benchmarks/perf/python-0.16.0.json", JAVA_ONLY)
		.rule("benchmarks/perf/", NONE)
		// the kept results and what labels them, which the Java harness's
		// tests reduce again and report on
		.rule("benchmarks/results/", union(BENCHMARK_TESTS, JAVA_ONLY))
		.rule("benchmarks/engines.json", union(BENCHMARK_TESTS, JAVA_ONLY))
		.rule("benchmarks/compose.yaml", union(BENCHMARK_TESTS, JAVA_ONLY))
		.rule("benchmarks/knee-range.json", union(BENCHMARK_TESTS, JAVA_ONLY))
		.rule("benchmarks/", BENCHMARK_TESTS)
		// the fixtures the golden files and recordings were made from,
		// which the API and app tests read
		.rule("parity/fixtures/", JAVA_ONLY)
		// the frozen engine's golden files and simulation baselines, which
		// the Java tests read
		.rule("parity/", JAVA)
		// the templates: every part loads them
		.rule("examples/", EVERY)
		// the documents the tests compare with what the engine or the
		// harness generates; no job reads the rest
		.rule("docs/assumptions.md", JAVA_ONLY)
		.rule("docs/validation.md", union(BENCHMARK_TESTS, JAVA_ONLY))
		// stage 4's parity page's runs, which only the Java harness's parity
		// page test reads (the page itself is one of docs/validation/'s)
		.rule("docs/validation/simulation-parity.json", JAVA_ONLY)
		.rule("docs/validation/", union(BENCHMARK_TESTS, JAVA_ONLY))
		.rule("docs/", NONE)
		// for people and assistants only
		.rule(".claude/", NONE)
		.rule("AGENTS.md", NONE)
		.rule("CLAUDE.md", NONE)
		.rule("CODE_OF_CONDUCT.md", NONE)
		.rule("CONTRIBUTING.md", NONE)
		.rule("IMPLEMENTATION_STATUS.md", NONE)
		.rule("LICENSE", NONE)
		.rule("NOTICE", NONE)
		.rule("README.md", NONE)
		.rule("SECURITY.md", NONE)
		.rules();

	private Rules() {
	}

	/**
	 * The jobs a change to one file needs: the first covering rule's, or every job.
	 * @param path the file, from the repository's root
	 * @return the jobs
	 */
	static Set<Job> neededBy(String path) {
		return STANDARD.stream().filter((rule) -> rule.covers(path)).findFirst().map(Rule::jobs).orElse(EVERY);
	}

	/**
	 * The jobs a change to these files needs.
	 * @param files the files, from the repository's root
	 * @return the jobs
	 */
	static Set<Job> jobs(Collection<String> files) {
		EnumSet<Job> needed = EnumSet.noneOf(Job.class);
		files.forEach((path) -> needed.addAll(neededBy(path)));
		return Set.copyOf(needed);
	}

	@SafeVarargs
	private static Set<Job> union(Set<Job>... sets) {
		EnumSet<Job> union = EnumSet.noneOf(Job.class);
		for (Set<Job> set : sets) {
			union.addAll(set);
		}
		return Set.copyOf(union);
	}

	/**
	 * The rules as the table above lists them, one call a rule.
	 */
	private static final class Table {

		private final List<Rule> rules = new ArrayList<>();

		Table rule(String path, Set<Job> jobs) {
			this.rules.add(new Rule(path, jobs));
			return this;
		}

		List<Rule> rules() {
			return List.copyOf(this.rules);
		}

	}

}
