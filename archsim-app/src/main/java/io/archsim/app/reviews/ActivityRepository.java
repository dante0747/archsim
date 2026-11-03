package io.archsim.app.reviews;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code audit_events} table, read a page at a time.
 */
@Repository
class ActivityRepository {

	private static final String SELECT_EVENTS = "SELECT id, action, target_kind, target_id, project_id, actor_user_id, "
			+ "actor_kind, meta::text AS meta, ip, created_at FROM audit_events";

	private final JdbcClient jdbc;

	private final JsonMapper json;

	ActivityRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * A project's changes, newest first.
	 * @param projectId the project
	 * @param range which changes
	 * @return up to {@code range.limit()} plus one, to tell whether there are more
	 */
	List<StoredEvent> ofProject(String projectId, HistoryRange range) {
		return events("project_id", projectId, range);
	}

	/**
	 * A workspace's changes, newest first.
	 * @param workspaceId the workspace
	 * @param range which changes
	 * @return up to {@code range.limit()} plus one, to tell whether there are more
	 */
	List<StoredEvent> ofWorkspace(String workspaceId, HistoryRange range) {
		return events("workspace_id", workspaceId, range);
	}

	private List<StoredEvent> events(String column, String id, HistoryRange range) {
		return this.jdbc
			.sql(SELECT_EVENTS + " WHERE " + column + " = :id AND (CAST(:before AS bigint) IS NULL OR id < :before) "
					+ "ORDER BY id DESC LIMIT :limit")
			.param("id", id)
			.param("before", range.before())
			.param("limit", range.limit() + 1)
			.query((rows, number) -> stored(rows))
			.list();
	}

	private StoredEvent stored(ResultSet rows) throws SQLException {
		String meta = rows.getString("meta");
		return new StoredEvent(rows.getLong("id"), rows.getString("action"), rows.getString("target_kind"),
				rows.getString("target_id"), rows.getString("project_id"), rows.getString("actor_user_id"),
				rows.getString("actor_kind"), (meta != null) ? this.json.readTree(meta) : null, rows.getString("ip"),
				rows.getTimestamp("created_at").toInstant());
	}

}
