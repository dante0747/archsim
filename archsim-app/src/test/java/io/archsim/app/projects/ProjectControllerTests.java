package io.archsim.app.projects;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.access.Principal;
import io.archsim.app.access.RequestedWorkspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * The projects' HTTP side: statuses, the workspace a request names, and the JSON of a
 * project.
 */
@WebMvcTest(ProjectController.class)
class ProjectControllerTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ProjectView PROJECT = new ProjectView("p1", "Checkout", null, 1,
			Instant.parse("2026-11-03T01:02:03.120Z"), Instant.parse("2026-11-03T01:02:03Z"), 0, 0,
			JSON.createObjectNode());

	@Autowired
	private MockMvcTester mvc;

	@MockitoBean
	private ProjectService projects;

	@MockitoBean
	private ProjectCreation creation;

	@Test
	void create_newProject_isCreated() {
		given(this.creation.create(any(), any(), any())).willReturn(new ProjectCreation.Created(PROJECT, false));

		assertThat(this.mvc.post().uri("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.hasStatus(201);
	}

	@Test
	void create_reusedCopy_isOk() {
		given(this.creation.create(any(), any(), any())).willReturn(new ProjectCreation.Created(PROJECT, true));

		assertThat(this.mvc.post().uri("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.hasStatusOk();
	}

	@Test
	void create_workspaceInTheHeader_isTheOneUsed() {
		given(this.creation.create(any(), any(), any())).willReturn(new ProjectCreation.Created(PROJECT, false));

		this.mvc.post()
			.uri("/api/v1/projects")
			.header("X-ArchSim-Workspace", "team1")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")
			.exchange();

		then(this.creation).should().create(any(Principal.class), eq(new RequestedWorkspace("team1")), any());
	}

	@Test
	void get_project_isItsJsonInCamelCaseWithPydanticTimes() {
		given(this.projects.get(any(), eq("p1"))).willReturn(PROJECT);

		assertThat(this.mvc.get().uri("/api/v1/projects/p1")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"id": "p1", "name": "Checkout", "description": null, "revision": 1,
				 "createdAt": "2026-11-03T01:02:03.120000Z", "updatedAt": "2026-11-03T01:02:03Z",
				 "nodeCount": 0, "scenarioCount": 0, "document": {}}""");
	}

	@Test
	void delete_project_isNoContent() {
		assertThat(this.mvc.delete().uri("/api/v1/projects/p1")).hasStatus(204);
	}

}
