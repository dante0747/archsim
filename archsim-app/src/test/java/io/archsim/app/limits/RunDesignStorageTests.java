package io.archsim.app.limits;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import io.archsim.app.database.TestDatabases;
import io.archsim.app.runs.RunPrunes;
import io.archsim.app.support.FullWorkspaces;
import io.archsim.app.support.StoredDesigns;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a workspace's stored bytes count of the designs its runs simulate, each stored
 * once however many runs simulate it (ADR-0110): the design once, in full, in each
 * workspace whose runs simulate it, until the last of those runs goes, whether someone
 * deletes it or the hourly prune does (ADR-0111). A database for each test, as a prune
 * reaches every project in it.
 */
class RunDesignStorageTests {

	private static final long MEGABYTE = WorkspaceLimits.MEGABYTE;

	private final JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(TestDatabases.migratedJdbcUrl()));

	private final StorageRepository storage = new StorageRepository(this.jdbc);

	@Test
	void storedBytes_runsOfOneDesign_countItOnce() {
		String workspace = workspace();
		String project = project(workspace);
		String design = design();
		run(project, design, 2);
		run(project, design, 1);

		assertThat(this.storage.storedBytes(workspace)).isBetween(MEGABYTE, 2 * MEGABYTE - 1);
	}

	@Test
	void storedBytes_aDesignTwoWorkspacesSimulate_countsInEachUntilItsRunsThereGo() {
		String design = design();
		String workspace = workspace();
		String other = workspace();
		run(project(workspace), design, 1);
		String othersRun = run(project(other), design, 1);
		long before = this.storage.storedBytes(workspace);
		long othersBefore = this.storage.storedBytes(other);

		this.jdbc.sql("DELETE FROM simulation_runs WHERE id = :id").param("id", othersRun).update();

		assertThat(before).isGreaterThanOrEqualTo(MEGABYTE);
		assertThat(othersBefore).isGreaterThanOrEqualTo(MEGABYTE);
		assertThat(this.storage.storedBytes(workspace)).isEqualTo(before);
		assertThat(this.storage.storedBytes(other)).isLessThan(MEGABYTE);
	}

	@Test
	void storedBytes_afterThePrune_countsOnlyTheDesignsOfTheRunsKept() {
		String workspace = workspace();
		String project = project(workspace);
		String retired = design();
		String current = design();
		run(project, retired, 3);
		run(project, current, 2);
		run(project, current, 1);
		long before = this.storage.storedBytes(workspace);

		RunPrunes.prune(this.jdbc, 1);

		assertThat(before).isBetween(2 * MEGABYTE, 3 * MEGABYTE - 1);
		assertThat(this.storage.storedBytes(workspace)).isBetween(MEGABYTE, before - MEGABYTE);
	}

	private String workspace() {
		String id = newId();
		this.jdbc.sql("INSERT INTO workspaces (id, name, kind, created_at) VALUES (:id, 'Lab', 'personal', now())")
			.param("id", id)
			.update();
		return id;
	}

	private String project(String workspaceId) {
		String id = newId();
		this.jdbc.sql("""
				INSERT INTO projects (id, workspace_id, name, document, schema_version, revision, created_at,
				                      updated_at)
				VALUES (:id, :workspace, 'Lab', '{}', '1.7', 1, now(), now())""")
			.param("id", id)
			.param("workspace", workspaceId)
			.update();
		return id;
	}

	/**
	 * A design of its own that stores a megabyte, as PostgreSQL cannot compress it.
	 * @return its digest
	 */
	private String design() {
		String padding = this.jdbc.sql("SELECT " + FullWorkspaces.incompressible(MEGABYTE))
			.query(String.class)
			.single();
		return StoredDesigns.store(this.jdbc, "{\"metadata\": {\"padding\": \"" + padding + "\"}}");
	}

	/**
	 * A run of a project that succeeded, without a report.
	 * @param projectId the project
	 * @param design the digest of the design it simulates
	 * @param minutesAgo how long ago it was queued
	 * @return its id
	 */
	private String run(String projectId, String design, int minutesAgo) {
		String id = newId();
		this.jdbc.sql("""
				INSERT INTO simulation_runs (id, project_id, workspace_id, status, progress, seed, engine_version,
				                             design_digest, created_at)
				SELECT :id, id, workspace_id, 'succeeded', 1, 1, 'test', :design, now() - make_interval(mins => :age)
				FROM projects WHERE id = :project""")
			.param("id", id)
			.param("design", design)
			.param("age", minutesAgo)
			.param("project", projectId)
			.update();
		return id;
	}

	private static String newId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

}
