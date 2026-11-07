package io.archsim.app.runs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.version.EngineVersions;

/**
 * The {@code simulation_runs} table: each row is a run and its own job. The API queues
 * runs and asks for them to stop; the workers claim them, record their progress and
 * outcome.
 */
@Repository
class RunRepository {

	private static final String COLUMNS = """
			id, project_id, workspace_id, label, scenario_id, scenario_name, status, progress, error, seed,
			engine_version, input_digest, result_digest, summary, wall_seconds, created_at, started_at, finished_at,
			group_id, seed_index, mode""";

	private final JdbcClient jdbc;

	private final JsonMapper json;

	RunRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * Queues a run: any worker may claim it from now on.
	 * @param queued the run, who starts it and when
	 */
	void add(QueuedRun queued) {
		NewRun run = queued.run();
		GroupPlace group = queued.group();
		this.jdbc.sql("""
				INSERT INTO simulation_runs (id, project_id, workspace_id, created_by, label, scenario_id,
				    scenario_name, status, progress, seed, engine_version, input_document, max_requests,
				    max_wall_seconds, mode, group_id, seed_index, attempts, created_at)
				VALUES (:id, :project, :workspace, :createdBy, :label, :scenarioId, :scenarioName, 'queued', 0,
				    :seed, :engine, CAST(:document AS jsonb), :maxRequests, :maxWallSeconds, :mode, :group,
				    :index, 0, :now)""")
			.param("id", queued.id())
			.param("project", run.destination().projectId())
			.param("workspace", run.destination().workspaceId())
			.param("createdBy", queued.createdBy())
			.param("label", run.label())
			.param("scenarioId", run.prepared().scenarioId())
			.param("scenarioName", run.prepared().scenarioName())
			.param("seed", run.prepared().seed())
			.param("engine", EngineVersions.ENGINE)
			.param("document", this.json.writeValueAsString(run.prepared().document()))
			.param("maxRequests", run.destination().limits().maxRequests())
			.param("maxWallSeconds", run.destination().limits().maxWallSeconds())
			.param("mode", run.mode().json())
			.param("group", (group != null) ? group.groupId() : null)
			.param("index", (group != null) ? group.index() : null)
			.param("now", Timestamp.from(queued.queuedAt()))
			.update();
	}

	Optional<StoredRun> find(String id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM simulation_runs WHERE id = :id")
			.param("id", id)
			.query((rows, number) -> stored(rows))
			.optional();
	}

	/**
	 * A project's latest runs.
	 * @param projectId the project
	 * @param limit how many at most
	 * @return the runs, newest first; runs stored at the same instant (by two of the
	 * API's servers) by id, so the list keeps its order
	 */
	List<RunView> ofProject(String projectId, int limit) {
		return this.jdbc
			.sql("SELECT " + COLUMNS + " FROM simulation_runs WHERE project_id = :project "
					+ "ORDER BY created_at DESC, id DESC LIMIT :limit")
			.param("project", projectId)
			.param("limit", limit)
			.query((rows, number) -> stored(rows).view())
			.list();
	}

	/**
	 * A run's report, read.
	 * @param id the run
	 * @return the report, if the run has one
	 */
	Optional<JsonNode> reportDocument(String id) {
		return report(id).map(this.json::readTree);
	}

	/**
	 * A run's report, as stored.
	 * @param id the run
	 * @return the report's JSON, if the run has one
	 */
	Optional<String> report(String id) {
		return this.jdbc.sql("SELECT report::text FROM simulation_runs WHERE id = :id AND report IS NOT NULL")
			.param("id", id)
			.query(String.class)
			.optional();
	}

	/**
	 * The design a run simulates, as stored.
	 * @param id the run
	 * @return the design's JSON
	 */
	String input(String id) {
		return this.jdbc.sql("SELECT input_document::text FROM simulation_runs WHERE id = :id")
			.param("id", id)
			.query(String.class)
			.single();
	}

	/**
	 * Asks for a run to stop: a queued run is cancelled now; a running one is marked, and
	 * its worker stops it after its next renewal.
	 * @param id the run
	 * @param now the time
	 */
	void requestCancel(String id, Instant now) {
		Timestamp at = Timestamp.from(now);
		int cancelled = this.jdbc
			.sql("UPDATE simulation_runs SET status = 'cancelled', finished_at = :now, cancel_requested_at = :now "
					+ "WHERE id = :id AND status = 'queued'")
			.param("now", at)
			.param("id", id)
			.update();
		if (cancelled == 0) {
			this.jdbc
				.sql("UPDATE simulation_runs SET cancel_requested_at = COALESCE(cancel_requested_at, :now) "
						+ "WHERE id = :id AND status = 'running'")
				.param("now", at)
				.param("id", id)
				.update();
		}
	}

	/**
	 * The design a run simulates, read.
	 * @param id the run
	 * @return the design
	 */
	JsonNode inputDocument(String id) {
		return this.json.readTree(input(id));
	}

	void delete(String id) {
		this.jdbc.sql("DELETE FROM simulation_runs WHERE id = :id").param("id", id).update();
	}

	/**
	 * The runs of a seed group.
	 * @param groupId the group
	 * @return its runs, in seed order
	 */
	List<RunView> ofGroup(String groupId) {
		return this.jdbc
			.sql("SELECT " + COLUMNS + " FROM simulation_runs WHERE group_id = :group "
					+ "ORDER BY seed_index, created_at")
			.param("group", groupId)
			.query((rows, number) -> stored(rows).view())
			.list();
	}

	/**
	 * The objectives of the design a run simulates.
	 * @param id the run
	 * @return the objectives, or none
	 */
	Optional<JsonNode> objectives(String id) {
		return this.jdbc
			.sql("SELECT (input_document -> 'objectives')::text FROM simulation_runs WHERE id = :id "
					+ "AND jsonb_typeof(input_document -> 'objectives') = 'object'")
			.param("id", id)
			.query(String.class)
			.optional()
			.map(this.json::readTree);
	}

	private StoredRun stored(ResultSet rows) throws SQLException {
		RunView view = new RunView(rows.getString("id"), rows.getString("project_id"), rows.getString("label"),
				rows.getString("scenario_id"), rows.getString("scenario_name"), rows.getString("status"),
				rows.getDouble("progress"), json(rows.getString("error")), rows.getLong("seed"),
				rows.getString("engine_version"), rows.getString("input_digest"), rows.getString("result_digest"),
				json(rows.getString("summary")), rows.getObject("wall_seconds", Double.class),
				rows.getTimestamp("created_at").toInstant(), instant(rows.getTimestamp("started_at")),
				instant(rows.getTimestamp("finished_at")), null, rows.getString("group_id"),
				rows.getObject("seed_index", Integer.class), rows.getString("mode"));
		return new StoredRun(rows.getString("workspace_id"), view);
	}

	private @Nullable JsonNode json(@Nullable String text) {
		return (text != null) ? this.json.readTree(text) : null;
	}

	private static @Nullable Instant instant(@Nullable Timestamp time) {
		return (time != null) ? time.toInstant() : null;
	}

}
