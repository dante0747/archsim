package io.archsim.app.workspaces;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code memberships} table, with each member's account.
 */
@Repository
class MemberRepository {

	private static final String MEMBERS = """
			SELECT m.user_id, u.name, u.email, u.avatar_url, m.role, m.created_at
			FROM memberships m JOIN users u ON u.id = m.user_id""";

	private final JdbcClient jdbc;

	MemberRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * A workspace's members.
	 * @param workspaceId the workspace
	 * @return its members, the longest-standing first; members who joined at the same
	 * instant (through two of the API's servers) by user id, so the list keeps its order
	 */
	List<MemberView> of(String workspaceId) {
		return this.jdbc.sql(MEMBERS + " WHERE m.workspace_id = :workspace ORDER BY m.created_at, m.user_id")
			.param("workspace", workspaceId)
			.query((rows, number) -> member(rows))
			.list();
	}

	Optional<MemberView> find(String workspaceId, String userId) {
		return this.jdbc.sql(MEMBERS + " WHERE m.workspace_id = :workspace AND m.user_id = :user")
			.param("workspace", workspaceId)
			.param("user", userId)
			.query((rows, number) -> member(rows))
			.optional();
	}

	int owners(String workspaceId) {
		return this.jdbc.sql("SELECT count(*) FROM memberships WHERE workspace_id = :workspace AND role = 'owner'")
			.param("workspace", workspaceId)
			.query(Integer.class)
			.single();
	}

	void setRole(String workspaceId, String userId, String role) {
		this.jdbc.sql("UPDATE memberships SET role = :role WHERE workspace_id = :workspace AND user_id = :user")
			.param("role", role)
			.param("workspace", workspaceId)
			.param("user", userId)
			.update();
	}

	void remove(String workspaceId, String userId) {
		this.jdbc.sql("DELETE FROM memberships WHERE workspace_id = :workspace AND user_id = :user")
			.param("workspace", workspaceId)
			.param("user", userId)
			.update();
	}

	private static MemberView member(ResultSet rows) throws SQLException {
		return new MemberView(rows.getString("user_id"), rows.getString("name"), rows.getString("email"),
				rows.getString("avatar_url"), rows.getString("role"), rows.getTimestamp("created_at").toInstant());
	}

}
