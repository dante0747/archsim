package io.archsim.tools.changes;

/**
 * The jobs of {@code .github/workflows/ci.yml} that run only when a change needs them, in
 * the order the {@code changes} job reports them. {@code benchmark_tests} is a step of
 * the Python job, about 9 of its minutes on a hosted runner: the accuracy benchmarks'
 * tests, which calibrate, simulate and render {@code docs/validation.md} with the Java
 * engine (ADR-0062). Only a change to the engine or to a file they read can change their
 * result.
 */
enum Job {

	/** Python (harness, scripts). */
	PYTHON("python"),

	/** The accuracy benchmarks' tests, a step of the Python job. */
	BENCHMARK_TESTS("benchmark_tests"),

	/** The Java build. */
	JAVA("java"),

	/** The archsim-check action's own check. */
	ARCHSIM_CHECK("archsim_check"),

	/** The web app. */
	WEB("web"),

	/** The end-to-end tests. */
	E2E("e2e");

	private final String id;

	Job(String id) {
		this.id = id;
	}

	/**
	 * The job's name, as {@code ci.yml}'s outputs name it.
	 * @return the name
	 */
	String id() {
		return this.id;
	}

}
