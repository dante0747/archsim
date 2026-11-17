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
 * change to it alone will not run the test. A change that deletes a file also runs the
 * website's job, wherever the file is: the docs may link to it.
 */
final class Rules {

	/** Every job: CI itself, the templates, a path no rule names. */
	static final Set<Job> EVERY = Set.copyOf(EnumSet.allOf(Job.class));

	private static final Set<Job> NONE = Set.of();

	private static final Set<Job> JAVA = Set.of(Job.JAVA, Job.E2E);

	private static final Set<Job> JAVA_ONLY = Set.of(Job.JAVA);

	/**
	 * What the website is built from (ADR-0078): its build fails on a broken link, which
	 * would otherwise first fail when main deploys it.
	 */
	private static final Set<Job> SITE = Set.of(Job.SITE);

	/**
	 * The documents the Java tests compare with what they generate, which the website
	 * renders.
	 */
	private static final Set<Job> GENERATED_DOCUMENTS = union(JAVA_ONLY, SITE);

	/**
	 * The archsim-check action builds the Java CLI into its image from these modules and
	 * the Gradle build (ADR-0060), so its job checks a change to any of them.
	 */
	private static final Set<Job> CHECK_ACTION = Set.of(Job.ARCHSIM_CHECK);

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
		// the Java harness's tests read its cpuset loop
		.rule(".github/workflows/benchmark.yml", JAVA_ONLY)
		// the tooling's tests check the release workflow's check of its tag
		// (ADR-0077)
		.rule(".github/workflows/release.yml", JAVA_ONLY)
		.rule(".github/", NONE)
		// The web app, whose *.site.tsx routes are the website. The Java
		// harness's ReportParityTests compares the accuracy page's copy with
		// docs/validation/. archsim-engine's GeneratedFileTests checks the
		// fixtures that `./gradlew :archsim-engine:generate` writes from the
		// starter; archsim-model's DesignDocumentsTests reads the starter too.
		.rule("apps/web/src/data/accuracy.json", Set.of(Job.WEB, Job.E2E, Job.JAVA, Job.SITE))
		.rule("apps/web/src/test/fixtures/starter.json", WEB_FIXTURES)
		.rule("apps/web/src/test/fixtures/catalog.json", WEB_FIXTURES)
		.rule("apps/web/src/test/fixtures/analysis-starter.json", WEB_FIXTURES)
		// the end-to-end launcher: the Java tests check the worker's
		// command line in it
		.rule("apps/web/e2e/api-server.mjs", Set.of(Job.WEB, Job.JAVA, Job.E2E))
		.rule("apps/web/", Set.of(Job.WEB, Job.E2E, Job.SITE))
		// The Java build. The black-box API tests, the command line, the MCP
		// server, the harness and the tooling are not part of the app the
		// end-to-end tests run.
		.rule("archsim-api-tests/", JAVA_ONLY)
		.rule("archsim-cli/", union(JAVA_ONLY, CHECK_ACTION))
		.rule("archsim-mcp/", JAVA_ONLY)
		// the harness, whose tests read the kept results under
		// benchmarks/results/ (ADR-0068)
		.rule("archsim-benchmarks/", JAVA_ONLY)
		// the tooling, whose tests the Java build runs
		.rule("archsim-tools/", JAVA_ONLY)
		.rule("archsim-app/", JAVA)
		.rule("archsim-engine/", union(JAVA, CHECK_ACTION))
		.rule("archsim-model/", union(JAVA, CHECK_ACTION))
		.rule("build-logic/", union(JAVA, CHECK_ACTION))
		.rule("config/", JAVA)
		.rule("gradle/", union(JAVA, CHECK_ACTION))
		// ArchSim's version, which the web app's tests hold its package to
		// (ADR-0076)
		.rule("gradle.properties", union(JAVA, CHECK_ACTION, Set.of(Job.WEB)))
		.rule("gradlew", union(JAVA, CHECK_ACTION))
		.rule("gradlew.bat", JAVA)
		.rule("settings.gradle.kts", union(JAVA, CHECK_ACTION))
		// the engines' speed (the frozen engine's, and the Java speed
		// task's): the Java harness's parity page test reads two of these
		// files, and nothing reads the earlier measurements
		.rule("benchmarks/perf/java-port.json", JAVA_ONLY)
		.rule("benchmarks/perf/python-0.16.0.json", JAVA_ONLY)
		.rule("benchmarks/perf/", NONE)
		// the stacks, their load, the kept results and what labels them,
		// which the Java harness's tests read
		.rule("benchmarks/", JAVA_ONLY)
		// the hosted kit: archsim-app's ComposeFileTests and the release's
		// check hold its compose file to the release (ADR-0080); no job
		// reads the rest of it
		.rule("deploy/compose.yaml", JAVA_ONLY)
		.rule("deploy/", NONE)
		// the fixtures the golden files and recordings were made from,
		// which the API and app tests read
		.rule("parity/fixtures/", JAVA_ONLY)
		// the frozen engine's golden files and simulation baselines, which
		// the Java tests read
		.rule("parity/", JAVA)
		// the templates: every part loads them
		.rule("examples/", EVERY)
		// the documents the tests compare with what the engine or the
		// harness generates; the website renders them all
		.rule("docs/assumptions.md", GENERATED_DOCUMENTS)
		.rule("docs/validation.md", GENERATED_DOCUMENTS)
		.rule("docs/validation/", GENERATED_DOCUMENTS)
		.rule("docs/", SITE)
		// for people and assistants only
		.rule(".claude/", NONE)
		.rule("AGENTS.md", NONE)
		.rule("CLAUDE.md", NONE)
		.rule("CODE_OF_CONDUCT.md", NONE)
		.rule("CONTRIBUTING.md", NONE)
		.rule("IMPLEMENTATION_STATUS.md", NONE)
		.rule("LICENSE", NONE)
		// the website renders the changelog, its home page reads the README,
		// and its footer NOTICE's copyright line
		.rule("CHANGELOG.md", SITE)
		.rule("NOTICE", SITE)
		.rule("README.md", SITE)
		.rule("SECURITY.md", NONE)
		.rules();

	private Rules() {
	}

	// the jobs a path needs: the first covering rule's, or every job
	private static Set<Job> ruleFor(String path) {
		return STANDARD.stream().filter((rule) -> rule.covers(path)).findFirst().map(Rule::jobs).orElse(EVERY);
	}

	/**
	 * The jobs a change to one file needs: its path's, and the website's when the change
	 * deleted it, since the docs may link to it.
	 * @param file the file
	 * @return the jobs
	 */
	static Set<Job> neededBy(ChangedFile file) {
		Set<Job> jobs = ruleFor(file.path());
		return file.deleted() ? union(jobs, SITE) : jobs;
	}

	/**
	 * The jobs a change to these files needs.
	 * @param files the files
	 * @return the jobs
	 */
	static Set<Job> jobs(Collection<ChangedFile> files) {
		EnumSet<Job> needed = EnumSet.noneOf(Job.class);
		files.forEach((file) -> needed.addAll(neededBy(file)));
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
