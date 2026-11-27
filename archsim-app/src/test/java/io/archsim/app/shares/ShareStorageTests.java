package io.archsim.app.shares;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.archsim.app.support.FullWorkspaces;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A share link stores a copy of a run's results, so a workspace that stores as much as
 * its kind may makes none, and its maker's daily limit is not charged for it (ADR-0109).
 * Links are made with the workspace locked, so a link checks again what a write that held
 * the lock stored. A signed-in person may own a sandbox, whose 10 MB a test fills.
 */
class ShareStorageTests extends ShareTestSupport {

	@Test
	void create_inASandboxStoringItsQuota_isAConflict() {
		SignedIn ada = signedIn("Ada");
		String run = runInAFullSandbox(ada);

		MvcTestResult refused = share(ada, run, "{}");

		assertThat(refused).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo(FullWorkspaces.FULL_SANDBOX);
	}

	@Test
	void create_inASandboxStoringItsQuota_isNotCountedAgainstTheDay() {
		SignedIn ada = signedIn("Ada");
		String run = runInAFullSandbox(ada);

		share(ada, run, "{}");

		assertThat(sharesCounted(ada)).isZero();
	}

	@Test
	void create_whileAnotherWriteFillsTheSandbox_isAConflictAndMakesNoLink() {
		SignedIn ada = signedIn("Ada");
		String project = projectInASandbox(ada);
		String run = run(project, "succeeded");

		MvcTestResult refused = FullWorkspaces.fillWhileWaiting(jdbc(), anotherDataSource(), project,
				() -> share(ada, run, "{}"));

		assertThat(refused).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo(FullWorkspaces.FULL_SANDBOX);
		assertThat(linksTo(run)).isZero();
	}

	/**
	 * A finished run in a sandbox someone owns, whose project stores the sandbox's whole
	 * quota.
	 * @param owner who owns the sandbox
	 * @return the run's id
	 */
	private String runInAFullSandbox(SignedIn owner) {
		String project = projectInASandbox(owner);
		String run = run(project, "succeeded");
		FullWorkspaces.fillSandbox(jdbc(), project);
		return run;
	}

	/**
	 * A project in a new sandbox someone owns.
	 * @param owner who owns the sandbox
	 * @return the project's id
	 */
	private String projectInASandbox(SignedIn owner) {
		String sandbox = newId();
		jdbc().sql("INSERT INTO workspaces (id, name, kind, created_at) VALUES (:id, 'Lab', 'sandbox', now())")
			.param("id", sandbox)
			.update();
		member(sandbox, owner.userId(), "owner");
		return project(owner, sandbox);
	}

	/**
	 * The share links to a run.
	 * @param runId the run
	 * @return how many
	 */
	private int linksTo(String runId) {
		return jdbc().sql("SELECT count(*) FROM shared_reports WHERE run_id = :run")
			.param("run", runId)
			.query(Integer.class)
			.single();
	}

	/**
	 * The share links someone has been counted for, in every window.
	 * @param person who
	 * @return how many
	 */
	private int sharesCounted(SignedIn person) {
		return jdbc().sql("SELECT coalesce(sum(count), 0) FROM rate_limits WHERE key = :key")
			.param("key", "user:" + person.userId() + ":shares")
			.query(Integer.class)
			.single();
	}

}
