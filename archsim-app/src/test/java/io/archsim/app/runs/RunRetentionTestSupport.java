package io.archsim.app.runs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.config.TestProperties;
import io.archsim.app.database.TestDatabases;

/**
 * What the tests of pruning runs share: a database for each test, as a prune reaches
 * every project in it, a project in its default workspace, runs queued a given number of
 * minutes ago, and the retention of a server that keeps a project's newest {@value #KEPT}
 * runs.
 */
abstract class RunRetentionTestSupport {

	/** The runs a project keeps. */
	static final int KEPT = 3;

	/** Any time: runs are kept by their number, not their age. */
	static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

	private final String database = TestDatabases.migratedJdbcUrl();

	private final JdbcClient jdbc = JdbcClient.create(new DriverManagerDataSource(this.database));

	private final RunRetention retention = new RunRetention(new RunRepository(this.jdbc, JsonMapper.builder().build()),
			new RunGroupRepository(this.jdbc), TestProperties.with("run-history-limit=" + KEPT));

	RunRetention retention() {
		return this.retention;
	}

	JdbcClient jdbc() {
		return this.jdbc;
	}

	/**
	 * Connections of their own to the test's database, as another API instance has.
	 * @return the source of the connections
	 */
	DataSource anotherDataSource() {
		return new DriverManagerDataSource(this.database);
	}

	/**
	 * A new project, in the default workspace.
	 * @return its id
	 */
	String project() {
		String id = newId();
		this.jdbc.sql("""
				INSERT INTO projects (id, workspace_id, name, document, schema_version, revision, created_at,
				                      updated_at)
				VALUES (:id, 'default', 'Lab', '{}', '1.7', 1, now(), now())""").param("id", id).update();
		return id;
	}

	/**
	 * A run of a project that succeeded.
	 * @param projectId the project
	 * @param minutesAgo how long ago it was queued
	 * @return its id
	 */
	String run(String projectId, int minutesAgo) {
		return run(projectId, minutesAgo, "succeeded");
	}

	/**
	 * A run of a project, as a worker leaves it.
	 * @param projectId the project
	 * @param minutesAgo how long ago it was queued
	 * @param status its status
	 * @return its id
	 */
	String run(String projectId, int minutesAgo, String status) {
		String id = newId();
		this.jdbc.sql("""
				INSERT INTO simulation_runs (id, project_id, workspace_id, status, progress, seed, engine_version,
				                             input_document, created_at)
				VALUES (:id, :project, 'default', :status, 1, 1, 'test', '{}', now() - make_interval(mins => :age))""")
			.param("id", id)
			.param("project", projectId)
			.param("status", status)
			.param("age", minutesAgo)
			.update();
		return id;
	}

	/**
	 * The newest runs a project keeps, queued in the last minutes: a run queued longer
	 * ago is past its limit.
	 * @param projectId the project
	 */
	void newestRuns(String projectId) {
		for (int minutesAgo = 1; minutesAgo <= KEPT; minutesAgo++) {
			run(projectId, minutesAgo);
		}
	}

	/**
	 * A seed group of a project, with a run that succeeded for each age given.
	 * @param projectId the project
	 * @param minutesAgo how long ago each run was queued, in seed order
	 * @return the group's id
	 */
	String group(String projectId, int... minutesAgo) {
		String groupId = newId();
		this.jdbc.sql("""
				INSERT INTO run_groups (id, workspace_id, project_id, kind, base_seed, size, created_at)
				VALUES (:id, 'default', :project, 'seeds', 1, :size, now())""")
			.param("id", groupId)
			.param("project", projectId)
			.param("size", minutesAgo.length)
			.update();
		for (int index = 0; index < minutesAgo.length; index++) {
			String runId = run(projectId, minutesAgo[index]);
			this.jdbc.sql("UPDATE simulation_runs SET group_id = :group, seed_index = :index WHERE id = :id")
				.param("group", groupId)
				.param("index", index)
				.param("id", runId)
				.update();
		}
		return groupId;
	}

	/**
	 * The runs of a seed group.
	 * @param groupId the group
	 * @return their ids, in seed order
	 */
	List<String> runsOfGroup(String groupId) {
		return this.jdbc.sql("SELECT id FROM simulation_runs WHERE group_id = :group ORDER BY seed_index")
			.param("group", groupId)
			.query((rows, number) -> rows.getString("id"))
			.list();
	}

	/**
	 * A project's runs.
	 * @param projectId the project
	 * @return their ids, oldest first
	 */
	List<String> runsOf(String projectId) {
		return this.jdbc.sql("SELECT id FROM simulation_runs WHERE project_id = :project ORDER BY created_at, id")
			.param("project", projectId)
			.query((rows, number) -> rows.getString("id"))
			.list();
	}

	/**
	 * Someone's approval of a run, as signing it off stores it.
	 * @param runId the run
	 */
	void signOff(String runId) {
		this.jdbc.sql("""
				INSERT INTO review_signoffs (id, project_id, run_id, project_revision, decision, created_at)
				SELECT :id, project_id, id, 1, 'approved', now() FROM simulation_runs WHERE id = :run""")
			.param("id", newId())
			.param("run", runId)
			.update();
	}

	/**
	 * A comment on a run, as writing one stores it.
	 * @param runId the run
	 */
	void comment(String runId) {
		this.jdbc.sql("""
				INSERT INTO comments (id, workspace_id, project_id, anchor_kind, anchor_id, run_id, body, created_at)
				SELECT :id, workspace_id, project_id, 'run', id, id, 'Why is p95 so high?', now()
				FROM simulation_runs WHERE id = :run""").param("id", newId()).param("run", runId).update();
	}

	static String newId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

}
