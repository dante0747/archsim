package io.archsim.tools.changes;

/**
 * The jobs of {@code .github/workflows/ci.yml} that run only when a change needs them, in
 * the order the {@code changes} job reports them: the Java build, the archsim-check
 * action's own check, the web app, the end-to-end tests and the website. The commit
 * messages' check always runs and is not one of them.
 */
enum Job {

	/** The Java build. */
	JAVA("java"),

	/** The archsim-check action's own check. */
	ARCHSIM_CHECK("archsim_check"),

	/** The web app. */
	WEB("web"),

	/** The end-to-end tests. */
	E2E("e2e"),

	/**
	 * The website's build and browser checks, which fail on any broken link (ADR-0078).
	 */
	SITE("site");

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
