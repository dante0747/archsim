package io.archsim.app.runs;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.run.SimulationLimitException;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

/**
 * Starting a run and reading it back: queued with the design the engine prepared, under
 * the workspace's limits, for the workers to take.
 */
class RunsTests extends RunTestSupport {

	@Test
	void create_design_isQueuedAsTheCurrentDesign() {
		prepares(42, null, true);
		String project = project("team", "editor");

		JsonNode run = started(start(project, "{}"));

		assertThat(run.path("status").asString()).isEqualTo("queued");
		assertThat(run.path("label").asString()).isEqualTo("Current design");
		assertThat(run.path("seed").asLong()).isEqualTo(42);
	}

	@Test
	void create_design_storesWhatTheEnginePrepared() {
		prepares(42, null, true);
		String project = project("team", "editor");

		JsonNode run = started(start(project, "{}"));

		assertThat(json(this.mvc().get().uri("/api/v1/runs/" + run.path("id").asString() + "/input").exchange()))
			.isEqualTo(PREPARED);
	}

	@Test
	void create_inSandbox_queuesTheRowAWorkerTakesWithTheSandboxsLimits() {
		prepares(42, null, true);
		String project = project("sandbox", "editor");

		String run = started(start(project, "{\"mode\": \"sampled\"}")).path("id").asString();

		assertThat(this.jdbc()
			.sql("SELECT status, attempts, max_requests, max_wall_seconds, mode FROM simulation_runs WHERE id = :id")
			.param("id", run)
			.query()
			.singleRow()).contains(entry("status", "queued"), entry("attempts", 0), entry("max_requests", 200_000L),
					entry("max_wall_seconds", 20.0), entry("mode", "sampled"));
	}

	@Test
	void create_inSandbox_asksTheEngineWithinTheSandboxsLimits() {
		prepares(42, null, true);
		String project = project("sandbox", "editor");

		start(project, "{}");

		then(preparation()).should()
			.prepare(argThat(
					(PrepareRequest request) -> request.maxRequests() == 200_000 && request.maxWallSeconds() == 20.0));
	}

	@Test
	void create_scenario_isLabelledWithItsName() {
		prepares(42, "Black Friday", true);
		String project = project("team", "editor");

		JsonNode run = started(start(project, "{\"scenarioId\": \"black-friday\"}"));

		assertThat(run.path("label").asString()).isEqualTo("Black Friday");
		assertThat(run.path("scenarioId").asString()).isEqualTo("black-friday");
	}

	@Test
	void create_quickCheck_isLabelledSo() {
		prepares(42, null, true);
		String project = project("team", "editor");

		JsonNode run = started(start(project, "{\"mode\": \"sampled\"}"));

		assertThat(run.path("label").asString()).isEqualTo("Current design · quick check");
	}

	@Test
	void create_quickCheckTheDesignCannotHave_isRefusedSayingWhy() {
		prepares(42, null, false);
		String project = project("team", "editor");

		assertThat(start(project, "{\"mode\": \"sampled\"}")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Cannot run a quick check: the traffic is not constant");
	}

	@Test
	void create_designTheEngineCannotRead_isRefusedWithItsIssues() {
		willThrow(new DocumentInvalidException(List.of(Issue.error("document.schema", "The document is not valid"))))
			.given(preparation())
			.prepare(any());
		String project = project("team", "editor");

		assertThat(start(project, "{}")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Cannot simulate: The document is not valid");
	}

	@Test
	void create_runOverItsLimits_isRefusedInTheEnginesWords() {
		willThrow(new SimulationLimitException("Expected 2,000,001 requests")).given(preparation()).prepare(any());
		String project = project("team", "editor");

		assertThat(start(project, "{}")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Expected 2,000,001 requests");
	}

	@Test
	void create_asViewer_isForbidden() {
		prepares(42, null, true);
		String project = project("team", "viewer");

		assertThat(start(project, "{}")).hasStatus(403);
	}

	@Test
	void create_run_isInTheHistory() {
		prepares(42, null, true);
		String project = project("team", "editor");

		started(start(project, "{}"));

		assertThat(actions(project)).containsExactly("run.create");
	}

	@Test
	void list_project_isItsRunsNewestFirst() {
		prepares(42, null, true);
		String project = project("team", "editor");
		String older = started(start(project, "{}")).path("id").asString();
		String newer = started(start(project, "{}")).path("id").asString();

		JsonNode runs = json(this.mvc().get().uri("/api/v1/projects/" + project + "/runs").exchange());

		assertThat(runs.valueStream().map((run) -> run.path("id").asString())).containsExactly(newer, older);
	}

	@Test
	void get_runOfAWorkspaceWithoutRole_isNotFound() {
		prepares(42, null, true);
		String project = project("team", "editor");
		String run = started(start(project, "{}")).path("id").asString();
		this.jdbc()
			.sql("DELETE FROM memberships WHERE user_id = 'local' AND workspace_id = "
					+ "(SELECT workspace_id FROM projects WHERE id = :id)")
			.param("id", project)
			.update();

		assertThat(this.mvc().get().uri("/api/v1/runs/" + run)).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Run '" + run + "' not found");
	}

	@Test
	void report_ofQueuedRun_isAConflict() {
		prepares(42, null, true);
		String run = started(start(project("team", "editor"), "{}")).path("id").asString();

		assertThat(this.mvc().get().uri("/api/v1/runs/" + run + "/report")).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Run is queued; no report available");
	}

	@Test
	void report_ofSucceededRun_isTheStoredReport() {
		prepares(42, null, true);
		String run = started(start(project("team", "editor"), "{}")).path("id").asString();
		finish(run, 120.0);

		assertThat(this.mvc().get().uri("/api/v1/runs/" + run + "/report")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.summary")
			.isEqualTo(Map.of("latencyMs", Map.of("p95", 120.0)));
	}

}
