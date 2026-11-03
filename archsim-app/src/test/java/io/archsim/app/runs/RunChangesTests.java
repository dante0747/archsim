package io.archsim.app.runs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import io.archsim.app.engine.PrepareRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.then;

/**
 * What editors do to runs: cancel, delete and run them again.
 */
class RunChangesTests extends RunTestSupport {

	@Test
	void cancel_queuedRun_isCancelledAtOnce() {
		prepares(42, null, true);
		String run = started(start(project("team", "editor"), "{}")).path("id").asString();

		JsonNode cancelled = json(post("/api/v1/runs/" + run + "/cancel", ""));

		assertThat(cancelled.path("status").asString()).isEqualTo("cancelled");
	}

	@Test
	void cancel_queuedRun_isInTheHistory() {
		prepares(42, null, true);
		String project = project("team", "editor");
		String run = started(start(project, "{}")).path("id").asString();

		post("/api/v1/runs/" + run + "/cancel", "");

		assertThat(actions(project)).containsExactly("run.create", "run.cancel");
	}

	@Test
	void cancel_runningRun_asksItsWorkerToStop() {
		prepares(42, null, true);
		String run = started(start(project("team", "editor"), "{}")).path("id").asString();
		markStatus(run, "running");

		post("/api/v1/runs/" + run + "/cancel", "");

		assertThat(this.jdbc()
			.sql("SELECT cancel_requested_at IS NOT NULL FROM simulation_runs WHERE id = :id")
			.param("id", run)
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void cancel_finishedRun_changesNothing() {
		prepares(42, null, true);
		String project = project("team", "editor");
		String run = started(start(project, "{}")).path("id").asString();
		finish(run, 100.0);

		JsonNode answer = json(post("/api/v1/runs/" + run + "/cancel", ""));

		assertThat(answer.path("status").asString()).isEqualTo("succeeded");
	}

	@Test
	void delete_run_isGone() {
		prepares(42, null, true);
		String run = started(start(project("team", "editor"), "{}")).path("id").asString();

		this.mvc().delete().uri("/api/v1/runs/" + run).exchange();

		assertThat(this.mvc().get().uri("/api/v1/runs/" + run)).hasStatus(404);
	}

	@Test
	void delete_run_isInTheHistory() {
		prepares(42, null, true);
		String project = project("team", "editor");
		String run = started(start(project, "{}")).path("id").asString();

		this.mvc().delete().uri("/api/v1/runs/" + run).exchange();

		assertThat(actions(project)).containsExactly("run.create", "run.delete");
	}

	@ParameterizedTest
	@ValueSource(strings = { "cancel", "rerun" })
	void change_asViewer_isForbidden(String change) {
		prepares(42, null, true);
		String project = project("team", "editor");
		String run = started(start(project, "{}")).path("id").asString();
		viewerOf(project);

		assertThat(post("/api/v1/runs/" + run + "/" + change, "")).hasStatus(403);
	}

	@Test
	void delete_asViewer_isForbidden() {
		prepares(42, null, true);
		String project = project("team", "editor");
		String run = started(start(project, "{}")).path("id").asString();
		viewerOf(project);

		assertThat(this.mvc().delete().uri("/api/v1/runs/" + run)).hasStatus(403);
	}

	@Test
	void rerun_run_reproducesItWithItsSeed() {
		prepares(42, null, true);
		String run = started(start(project("team", "editor"), "{\"label\": \"Baseline\"}")).path("id").asString();

		JsonNode again = started(post("/api/v1/runs/" + run + "/rerun", ""));

		assertThat(again.path("reproducesRunId").asString()).isEqualTo(run);
		assertThat(again.path("label").asString()).isEqualTo("Reproduce: Baseline");
		then(engine()).should().prepare(argThat((PrepareRequest request) -> Long.valueOf(42).equals(request.seed())));
	}

	@Test
	void rerun_runWithoutLabel_isNamedByTheStartOfItsId() {
		prepares(42, null, true);
		String run = started(start(project("team", "editor"), "{}")).path("id").asString();
		this.jdbc().sql("UPDATE simulation_runs SET label = NULL WHERE id = :id").param("id", run).update();

		JsonNode again = started(post("/api/v1/runs/" + run + "/rerun", ""));

		assertThat(again.path("label").asString()).isEqualTo("Reproduce: " + run.substring(0, 8));
	}

	@Test
	void rerun_quickCheck_isAQuickCheckAgain() {
		prepares(42, null, true);
		String run = started(start(project("team", "editor"), "{\"mode\": \"sampled\"}")).path("id").asString();

		JsonNode again = started(post("/api/v1/runs/" + run + "/rerun", ""));

		assertThat(again.path("mode").asString()).isEqualTo("sampled");
	}

	private void viewerOf(String projectId) {
		this.jdbc()
			.sql("UPDATE memberships SET role = 'viewer' WHERE user_id = 'local' AND workspace_id = "
					+ "(SELECT workspace_id FROM projects WHERE id = :id)")
			.param("id", projectId)
			.update();
	}

}
