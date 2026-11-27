package io.archsim.app.projects;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.archsim.app.support.FullWorkspaces;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A workspace that stores as much as its kind may takes no new project and no new design
 * until something is deleted; a rename still goes through (ADR-0109). A sandbox keeps its
 * 10 MB without the hosted limits; other kinds then have none.
 */
class ProjectStorageTests extends ProjectTestSupport {

	@Test
	void create_inASandboxStoringItsQuota_isAConflict() {
		fillSandbox("fullsb1");

		MvcTestResult refused = post("/api/v1/projects?workspace=fullsb1", "{}");

		assertThat(refused).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo(FullWorkspaces.FULL_SANDBOX);
	}

	@Test
	void update_ofANewDesignInASandboxStoringItsQuota_isAConflict() {
		fillSandbox("fullsb2");

		MvcTestResult refused = put("/api/v1/projects/fullsb2p", """
				{"document": {"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "Stored"},
				 "objectives": {"latencyP95Ms": 200}, "architecture": {"nodes": [], "edges": []}, "workload": {}}}""");

		assertThat(refused).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo(FullWorkspaces.FULL_SANDBOX);
	}

	@Test
	void update_ofANameInASandboxStoringItsQuota_isSaved() {
		fillSandbox("fullsb3");

		assertThat(put("/api/v1/projects/fullsb3p", "{\"name\": \"Renamed\"}")).hasStatus(200);
	}

	@Test
	void create_inAPersonalWorkspaceWithoutLimits_hasNoQuota() {
		workspace("fullme1", "personal", "owner");
		storedProject("fullme1p", "fullme1", "local", null);
		FullWorkspaces.pad(jdbc(), "fullme1p", FullWorkspaces.SANDBOX_QUOTA);

		assertThat(post("/api/v1/projects?workspace=fullme1", "{}")).hasStatus(201);
	}

	/** A sandbox the local user owns, whose one project stores its whole quota. */
	private void fillSandbox(String workspaceId) {
		workspace(workspaceId, "sandbox", "owner");
		storedProject(workspaceId + "p", workspaceId, "local", null);
		FullWorkspaces.fillSandbox(jdbc(), workspaceId + "p");
	}

}
