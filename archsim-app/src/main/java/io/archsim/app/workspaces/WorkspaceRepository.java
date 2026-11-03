package io.archsim.app.workspaces;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code workspaces} and {@code memberships} tables.
 */
@Repository
class WorkspaceRepository {

	private final JdbcClient jdbc;

	WorkspaceRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The workspaces a user belongs to.
	 * @param userId the user
	 * @return each workspace with the user's role, in no order
	 */
	List<Membership> memberships(String userId) {
		return this.jdbc.sql("""
				SELECT w.id, w.name, w.kind, w.max_concurrent_runs, w.max_runs_per_hour, w.created_at, m.role
				FROM workspaces w JOIN memberships m ON m.workspace_id = w.id
				WHERE m.user_id = :user""").param("user", userId).query((rows, number) -> membership(rows)).list();
	}

	/**
	 * Stores a workspace with its owner.
	 * @param workspace the workspace
	 */
	void create(NewWorkspace workspace) {
		Timestamp at = Timestamp.from(workspace.createdAt());
		this.jdbc
			.sql("INSERT INTO workspaces (id, name, kind, created_by, created_at) "
					+ "VALUES (:id, :name, :kind, :owner, :now)")
			.param("id", workspace.id())
			.param("name", workspace.name())
			.param("kind", workspace.kind())
			.param("owner", workspace.ownerId())
			.param("now", at)
			.update();
		this.jdbc
			.sql("INSERT INTO memberships (workspace_id, user_id, role, created_at) "
					+ "VALUES (:id, :owner, 'owner', :now)")
			.param("id", workspace.id())
			.param("owner", workspace.ownerId())
			.param("now", at)
			.update();
	}

	private static Membership membership(ResultSet rows) throws SQLException {
		return new Membership(rows.getString("id"), rows.getString("name"), rows.getString("kind"),
				rows.getObject("max_concurrent_runs", Integer.class),
				rows.getObject("max_runs_per_hour", Integer.class), rows.getTimestamp("created_at").toInstant(),
				rows.getString("role"));
	}

}
