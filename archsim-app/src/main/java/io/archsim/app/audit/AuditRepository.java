package io.archsim.app.audit;

import java.sql.Timestamp;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes the history's events.
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

}
