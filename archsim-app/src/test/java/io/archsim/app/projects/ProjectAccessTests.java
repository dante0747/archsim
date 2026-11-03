package io.archsim.app.projects;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may do what with projects: someone without a role in a workspace learns nothing
 * about it ({@code 404}), and a role too weak for a change is refused ({@code 403}).
 */
class ProjectAccessTests extends ProjectTestSupport {

	@Test
	void get_projectInAWorkspaceWithoutARole_isNotFound() {
		workspace("hidden1", "team", "");
		storedProject("hidden1p", "hidden1", "local", null);

		assertThat(mvc().get().uri("/api/v1/projects/hidden1p")).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Project 'hidden1p' not found");
	}

	@Test
	void create_asViewer_isForbiddenNamingBothRoles() {
		workspace("readonly1", "team", "viewer");

		assertThat(post("/api/v1/projects?workspace=readonly1", "{}")).hasStatus(403)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("You are a viewer in this workspace; this needs editor access.");
	}

	@Test
	void update_asViewer_isForbidden() {
		workspace("readonly2", "team", "viewer");
		storedProject("readonly2p", "readonly2", "local", null);

		assertThat(put("/api/v1/projects/readonly2p", """
				{"name": "Mine now"}""")).hasStatus(403);
	}

	@Test
	void delete_asViewer_isForbidden() {
		workspace("readonly3", "team", "viewer");
		storedProject("readonly3p", "readonly3", "local", null);

		assertThat(mvc().delete().uri("/api/v1/projects/readonly3p")).hasStatus(403);
	}

	@Test
	void duplicate_asViewer_isForbidden() {
		workspace("readonly4", "team", "viewer");
		storedProject("readonly4p", "readonly4", "local", null);

		assertThat(post("/api/v1/projects/readonly4p/duplicate", "")).hasStatus(403);
	}

	@Test
	void get_asViewer_isTheProject() {
		workspace("readonly5", "team", "viewer");
		storedProject("readonly5p", "readonly5", "local", null);

		assertThat(mvc().get().uri("/api/v1/projects/readonly5p")).hasStatusOk();
	}

	@Test
	void list_workspaceWithoutARole_isNotFound() {
		assertThat(mvc().get().uri("/api/v1/projects").header("X-ArchSim-Workspace", "elsewhere")).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Workspace 'elsewhere' not found");
	}

	@Test
	void list_workspaceIdTooLong_isRefusedAsFastApiRefusesIt() {
		assertThat(mvc().get().uri("/api/v1/projects?workspace=" + "w".repeat(33))).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail[0].loc")
			.isEqualTo(List.of("query", "workspace"));
	}

	@Test
	void create_inSandboxHoldingFive_isAConflict() {
		workspace("sandbox1", "sandbox", "owner");
		for (int made = 0; made < 5; made++) {
			created(post("/api/v1/projects?workspace=sandbox1", "{}"));
		}

		MvcTestResult sixth = post("/api/v1/projects?workspace=sandbox1", "{}");

		assertThat(sixth).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("This workspace holds at most 5 projects: delete one first.");
	}

}
