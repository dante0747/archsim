package io.archsim.app.runs;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.archsim.app.support.FullWorkspaces;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A workspace that stores as much as its kind may starts no run, which would store its
 * design and its report, until something is deleted (ADR-0109). Runs are queued with the
 * workspace locked, so a start checks again what a write that held the lock stored.
 */
class RunStorageTests extends RunTestSupport {

	@Test
	void start_inASandboxStoringItsQuota_isAConflictAndQueuesNothing() {
		String project = project("sandbox", "owner");
		prepares(7, null, false);
		FullWorkspaces.fillSandbox(jdbc(), project);

		MvcTestResult refused = start(project, "{}");

		assertThat(refused).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo(FullWorkspaces.FULL_SANDBOX);
		assertThat(runCount(project)).isZero();
	}

	@Test
	void start_inASandboxBelowItsQuota_isQueued() {
		String project = project("sandbox", "owner");
		prepares(7, null, false);

		assertThat(start(project, "{}")).hasStatus(202);
	}

	@Test
	void start_whileAnotherWriteFillsTheSandbox_isAConflictAndQueuesNothing() {
		String project = project("sandbox", "owner");
		prepares(7, null, false);

		MvcTestResult refused = FullWorkspaces.fillWhileWaiting(jdbc(), anotherDataSource(), project,
				() -> start(project, "{}"));

		assertThat(refused).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo(FullWorkspaces.FULL_SANDBOX);
		assertThat(runCount(project)).isZero();
	}

}
