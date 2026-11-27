package io.archsim.app.projects;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With the hosted limits, a personal workspace holds at most 100 projects, as a sandbox
 * holds five (ADR-0109).
 */
class HostedProjectCapTests extends HostedTestSupport {

	private static final int PERSONAL_CAP = 100;

	@Test
	void create_inAPersonalWorkspaceHoldingAHundred_isAConflict() {
		SignedIn ada = signedIn("Ada");
		for (int made = 0; made < PERSONAL_CAP; made++) {
			project(ada);
		}

		MvcTestResult refused = send(ada, HttpMethod.POST, "/api/v1/projects", "{}");

		assertThat(refused).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("This workspace holds at most 100 projects: delete one first.");
	}

	@Test
	void create_inAPersonalWorkspaceHoldingFewer_isCreated() {
		SignedIn ada = signedIn("Ada");
		project(ada);

		assertThat(send(ada, HttpMethod.POST, "/api/v1/projects", "{}")).hasStatus(201);
	}

	private void project(SignedIn owner) {
		jdbc().sql("""
				INSERT INTO projects (id, workspace_id, created_by, name, document, schema_version, revision,
				                      created_at, updated_at)
				VALUES (:id, :workspace, :user, 'Shop', CAST('{"metadata": {"name": "Shop"}}' AS jsonb), '1.7', 1,
				        now(), now())""")
			.param("id", newId())
			.param("workspace", owner.workspaceId())
			.param("user", owner.userId())
			.update();
	}

}
