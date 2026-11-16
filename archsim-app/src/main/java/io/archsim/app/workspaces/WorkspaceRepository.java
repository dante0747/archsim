package io.archsim.app.workspaces;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.archsim.app.access.WorkspaceRole;

/**
 * The {@code workspaces} and {@code memberships} tables.
 */
@Repository
class WorkspaceRepository {

	private static final String COLUMNS = "w.id, w.name, w.kind, w.max_concurrent_runs, w.max_runs_per_hour, "
			+ "w.created_at";

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
		return this.jdbc
			.sql("SELECT " + COLUMNS + ", m.role FROM workspaces w JOIN memberships m ON m.workspace_id = w.id "
					+ "WHERE m.user_id = :user")
			.param("user", userId)
			.query((rows, number) -> new Membership(info(rows), rows.getString("role")))
			.list();
	}

	Optional<WorkspaceInfo> find(String id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM workspaces w WHERE w.id = :id")
			.param("id", id)
			.query((rows, number) -> info(rows))
			.optional();
	}

	/**
	 * Stores a workspace with its owner.
	 * @param workspace the workspace
	 */
	void create(NewWorkspace workspace) {
		this.jdbc
			.sql("INSERT INTO workspaces (id, name, kind, created_by, created_at) "
					+ "VALUES (:id, :name, :kind, :owner, :now)")
			.param("id", workspace.id())
			.param("name", workspace.name())
			.param("kind", workspace.kind())
			.param("owner", workspace.ownerId())
			.param("now", Timestamp.from(workspace.createdAt()))
			.update();
		addMember(workspace.id(), workspace.ownerId(), WorkspaceRole.OWNER.json(), workspace.createdAt());
	}

	/**
	 * Makes someone a member.
	 * @param workspaceId the workspace
	 * @param userId who joins
	 * @param role the role they join with
	 * @param at when they join
	 */
	void addMember(String workspaceId, String userId, String role, Instant at) {
		this.jdbc
			.sql("INSERT INTO memberships (workspace_id, user_id, role, created_at) "
					+ "VALUES (:workspace, :user, :role, :at)")
			.param("workspace", workspaceId)
			.param("user", userId)
			.param("role", role)
			.param("at", Timestamp.from(at))
			.update();
	}

	void rename(String id, String name) {
		this.jdbc.sql("UPDATE workspaces SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
	}

	/**
	 * Deletes a workspace, and with it its memberships, projects, runs and history.
	 * @param id the workspace
	 */
	void delete(String id) {
		this.jdbc.sql("DELETE FROM workspaces WHERE id = :id").param("id", id).update();
	}

	/**
	 * Deletes the sandboxes someone made, and with them everything a workspace's delete
	 * takes.
	 * @param userId who made them
	 */
	void deleteSandboxesOf(String userId) {
		this.jdbc.sql("DELETE FROM workspaces WHERE kind = :kind AND created_by = :user")
			.param("kind", Workspaces.SANDBOX)
			.param("user", userId)
			.update();
	}

	private static WorkspaceInfo info(ResultSet rows) throws SQLException {
		return new WorkspaceInfo(rows.getString("id"), rows.getString("name"), rows.getString("kind"),
				rows.getObject("max_concurrent_runs", Integer.class),
				rows.getObject("max_runs_per_hour", Integer.class), rows.getTimestamp("created_at").toInstant());
	}

}
