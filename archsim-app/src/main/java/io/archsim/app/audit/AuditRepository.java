package io.archsim.app.audit;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes the history's events, and deletes those it no longer keeps.
 */
@Repository
class AuditRepository {

	private final JdbcClient jdbc;

	AuditRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(AuditEvent event) {
		this.jdbc.sql("""
				INSERT INTO audit_events
				  (workspace_id, project_id, actor_user_id, actor_kind, action, target_kind, target_id, meta, ip,
				   created_at)
				VALUES (:workspace, :project, :user, :kind, :action, :targetKind, :targetId, CAST(:meta AS jsonb), :ip,
				        :at)""")
			.param("workspace", event.workspaceId())
			.param("project", event.projectId())
			.param("user", event.actor().userId())
			.param("kind", event.actor().kind())
			.param("action", event.action())
			.param("targetKind", event.targetKind())
			.param("targetId", event.targetId())
			.param("meta", (event.meta() != null) ? event.meta().toString() : null)
			.param("ip", event.actor().ip())
			.param("at", Timestamp.from(event.at()))
			.update();
	}

	/**
	 * Deletes, of the oldest events, those recorded before a time: the first by id, which
	 * grows with time. Events another transaction holds are left to it.
	 * @param before the time
	 * @param limit how many of the oldest events to look at
	 * @return how many it deleted
	 */
	int deleteOldestBefore(Instant before, int limit) {
		return this.jdbc.sql("""
				DELETE FROM audit_events WHERE created_at < :before AND id IN (
				    SELECT id FROM audit_events ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED)""")
			.param("before", Timestamp.from(before))
			.param("limit", limit)
			.update();
	}

}
