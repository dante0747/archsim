package io.archsim.app.runs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run's design is stored once, by its digest, however many runs simulate it, and goes
 * with the last run that refers to it: whether that run is deleted on its own or with its
 * project or workspace. Each test simulates a design of its own, so the tests' runs share
 * no design.
 */
class RunDesignsTests extends RunTestSupport {

	@Test
	void create_aSecondRunOfADesign_storesTheDesignOnce() {
		String project = project("team", "editor");
		preparesADesignOfItsOwn();
		started(start(project, "{}"));

		started(start(project, "{}"));

		assertThat(runCount(project)).isEqualTo(2);
		assertThat(designsStoredFor(project)).isOne();
	}

	@Test
	void create_seedGroup_storesItsDesignOnce() {
		String project = project("team", "editor");
		preparesADesignOfItsOwn();

		started(start(project, "{\"seeds\": 3}"));

		assertThat(runCount(project)).isEqualTo(3);
		assertThat(designsStoredFor(project)).isOne();
	}

	@Test
	void create_runsOfOneDesignInTwoWorkspaces_shareItsStoredDesign() {
		String project = project("team", "editor");
		String elsewhere = project("team", "editor");
		preparesADesignOfItsOwn();
		String run = started(start(project, "{}")).path("id").asString();

		String other = started(start(elsewhere, "{}")).path("id").asString();

		assertThat(digestOf(other)).isEqualTo(digestOf(run));
	}

	@Test
	void delete_runOfADesignAnotherRunSimulates_keepsTheDesign() {
		String project = project("team", "editor");
		ObjectNode design = preparesADesignOfItsOwn();
		String run = started(start(project, "{}")).path("id").asString();
		String other = started(start(project, "{}")).path("id").asString();
		String digest = digestOf(run);

		assertThat(this.mvc().delete().uri("/api/v1/runs/" + run)).hasStatus(204);

		assertThat(isStored(digest)).isTrue();
		assertThat(json(this.mvc().get().uri("/api/v1/runs/" + other + "/input").exchange())).isEqualTo(design);
	}

	@Test
	void delete_lastRunOfADesign_deletesTheDesign() {
		String project = project("team", "editor");
		preparesADesignOfItsOwn();
		String run = started(start(project, "{}")).path("id").asString();
		String digest = digestOf(run);

		assertThat(this.mvc().delete().uri("/api/v1/runs/" + run)).hasStatus(204);

		assertThat(isStored(digest)).isFalse();
	}

	@Test
	void deleteProject_designOnlyItsRunsSimulate_isDeleted() {
		String project = project("team", "owner");
		preparesADesignOfItsOwn();
		String digest = digestOf(started(start(project, "{}")).path("id").asString());

		assertThat(this.mvc().delete().uri("/api/v1/projects/" + project)).hasStatus(204);

		assertThat(isStored(digest)).isFalse();
	}

	@Test
	void deleteProject_designAnotherProjectsRunSimulates_isKept() {
		String project = project("team", "owner");
		String other = project("team", "owner");
		preparesADesignOfItsOwn();
		String digest = digestOf(started(start(project, "{}")).path("id").asString());
		started(start(other, "{}"));

		assertThat(this.mvc().delete().uri("/api/v1/projects/" + project)).hasStatus(204);

		assertThat(isStored(digest)).isTrue();
	}

	@Test
	void deleteWorkspace_designOnlyItsRunsSimulate_isDeleted() {
		String project = project("team", "owner");
		preparesADesignOfItsOwn();
		String digest = digestOf(started(start(project, "{}")).path("id").asString());

		assertThat(this.mvc().delete().uri("/api/v1/workspaces/" + workspaceOf(project))).hasStatus(204);

		assertThat(isStored(digest)).isFalse();
	}

	/**
	 * Makes the engine prepare every run as one design, which no other test's run has.
	 * @return the design
	 */
	private ObjectNode preparesADesignOfItsOwn() {
		ObjectNode design = PREPARED.deepCopy();
		design.putObject("metadata").put("name", id());
		prepares(42, (seed) -> {
			PlannedRun prepared = prepared(seed, null, true);
			return new PlannedRun(null, null, seed, design, prepared.quickCheck());
		});
		return design;
	}

	/**
	 * How many designs a project's runs refer to.
	 * @param projectId the project
	 * @return the stored designs its runs simulate
	 */
	private int designsStoredFor(String projectId) {
		return this.jdbc()
			.sql("SELECT count(*) FROM run_designs "
					+ "WHERE digest IN (SELECT design_digest FROM simulation_runs WHERE project_id = :project)")
			.param("project", projectId)
			.query(Integer.class)
			.single();
	}

	/**
	 * The digest of the design a run simulates.
	 * @param runId the run
	 * @return its design's digest
	 */
	private String digestOf(String runId) {
		return this.jdbc()
			.sql("SELECT design_digest FROM simulation_runs WHERE id = :id")
			.param("id", runId)
			.query(String.class)
			.single();
	}

	private boolean isStored(String digest) {
		return this.jdbc()
			.sql("SELECT count(*) FROM run_designs WHERE digest = :digest")
			.param("digest", digest)
			.query(Integer.class)
			.single() == 1;
	}

	private String workspaceOf(String projectId) {
		return this.jdbc()
			.sql("SELECT workspace_id FROM projects WHERE id = :id")
			.param("id", projectId)
			.query(String.class)
			.single();
	}

}
