package io.archsim.app.access;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Workspaces and memberships, as access decisions read them.
 */
@Repository
class AccessRepository {

	private final JdbcClient jdbc;

	AccessRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<WorkspaceRef> workspace(String id) {
		return this.jdbc.sql("SELECT id, kind FROM workspaces WHERE id = :id")
			.param("id", id)
			.query(WorkspaceRef.class)
			.optional();
	}

	/**
	 * A workspace, locked until the transaction ends. The lock lets rows that refer to
	 * the workspace still be written meanwhile ({@code NO KEY}).
	 * @param id the workspace's id
	 * @return the workspace, if it exists
	 */
	Optional<WorkspaceRef> lockWorkspace(String id) {
		return this.jdbc.sql("SELECT id, kind FROM workspaces WHERE id = :id FOR NO KEY UPDATE")
			.param("id", id)
			.query(WorkspaceRef.class)
			.optional();
	}

	Optional<String> role(String workspaceId, String userId) {
		return this.jdbc.sql("SELECT role FROM memberships WHERE workspace_id = :workspace AND user_id = :user")
			.param("workspace", workspaceId)
			.param("user", userId)
			.query(String.class)
			.optional();
	}

	/**
	 * The workspaces a user belongs to.
	 * @param userId the user
	 * @return the workspaces, oldest membership first
	 */
	List<WorkspaceRef> memberships(String userId) {
		return this.jdbc.sql("""
				SELECT w.id, w.kind FROM memberships m JOIN workspaces w ON w.id = m.workspace_id
				WHERE m.user_id = :user ORDER BY m.created_at""")
			.param("user", userId)
			.query((rows, number) -> new WorkspaceRef(rows.getString("id"), rows.getString("kind")))
			.list();
	}

}
