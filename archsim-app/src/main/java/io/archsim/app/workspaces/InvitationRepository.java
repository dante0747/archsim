package io.archsim.app.workspaces;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code invitations} table: links that make whoever accepts them a member, kept as
 * the SHA-256 of their token. One is open until it is accepted or expires. Also the
 * emails people verified ({@code identities}), which an invitation sent to an email
 * needs.
 */
@Repository
class InvitationRepository {

	private static final String COLUMNS = "id, workspace_id, role, email, created_at, expires_at, accepted_at";

	private final JdbcClient jdbc;

	InvitationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * A workspace's open invitations.
	 * @param workspaceId the workspace
	 * @param now the time it is
	 * @return the invitations not accepted and not expired, newest first; invitations
	 * made at the same instant (by two of the API's servers) by id, so the list keeps its
	 * order
	 */
	List<InvitationView> open(String workspaceId, Instant now) {
		return this.jdbc
			.sql("SELECT " + COLUMNS + " FROM invitations WHERE workspace_id = :workspace AND accepted_at IS NULL "
					+ "AND expires_at > :now ORDER BY created_at DESC, id DESC")
			.param("workspace", workspaceId)
			.param("now", Timestamp.from(now))
			.query((rows, number) -> view(rows))
			.list();
	}

	Optional<InvitationView> find(String id) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM invitations WHERE id = :id")
			.param("id", id)
			.query((rows, number) -> view(rows))
			.optional();
	}

	/**
	 * The open invitation a token belongs to, with what accepting it shows.
	 * @param hash the token's SHA-256
	 * @param now the time it is
	 * @return the invitation, if it is neither accepted nor expired
	 */
	Optional<OpenInvitation> openWithToken(String hash, Instant now) {
		return this.jdbc.sql("""
				SELECT i.id, i.workspace_id, i.role, i.email, i.created_at, i.expires_at, i.accepted_at,
				       w.name AS workspace_name, u.name AS invited_by
				FROM invitations i JOIN workspaces w ON w.id = i.workspace_id
				LEFT JOIN users u ON u.id = i.created_by
				WHERE i.token_hash = :hash AND i.accepted_at IS NULL AND i.expires_at > :now""")
			.param("hash", hash)
			.param("now", Timestamp.from(now))
			.query((rows, number) -> new OpenInvitation(view(rows), rows.getString("workspace_name"),
					rows.getString("invited_by")))
			.optional();
	}

	/**
	 * Stores an invitation.
	 * @param invitation the invitation
	 * @return the invitation as stored
	 */
	InvitationView add(NewInvitation invitation) {
		return this.jdbc.sql("""
				INSERT INTO invitations (id, workspace_id, token_hash, role, email, created_by, created_at, expires_at)
				VALUES (:id, :workspace, :hash, :role, :email, :createdBy, :createdAt, :expiresAt)
				RETURNING id, workspace_id, role, email, created_at, expires_at, accepted_at""")
			.param("id", invitation.id())
			.param("workspace", invitation.workspaceId())
			.param("hash", invitation.tokenHash())
			.param("role", invitation.role())
			.param("email", invitation.email())
			.param("createdBy", invitation.createdBy())
			.param("createdAt", Timestamp.from(invitation.createdAt()))
			.param("expiresAt", Timestamp.from(invitation.expiresAt()))
			.query((rows, number) -> view(rows))
			.single();
	}

	/**
	 * Marks an invitation accepted, if it is still open. Of two people accepting it at
	 * once, one claims it; the other waits for that claim to commit, then finds it used.
	 * @param id the invitation
	 * @param userId who accepts it
	 * @param now the time it is
	 * @return {@code true} when this call claimed it
	 */
	boolean claim(String id, String userId, Instant now) {
		return this.jdbc
			.sql("UPDATE invitations SET accepted_at = :now, accepted_by = :user "
					+ "WHERE id = :id AND accepted_at IS NULL AND expires_at > :now")
			.param("now", Timestamp.from(now))
			.param("user", userId)
			.param("id", id)
			.update() == 1;
	}

	void delete(String id) {
		this.jdbc.sql("DELETE FROM invitations WHERE id = :id").param("id", id).update();
	}

	/**
	 * Deletes some of the invitations that have expired, accepted or not, the first to
	 * expire first. Invitations another transaction holds are left to it.
	 * @param now the time it is
	 * @param limit how many to delete at most
	 * @return how many it deleted
	 */
	int deleteExpired(Instant now, int limit) {
		return this.jdbc.sql("""
				DELETE FROM invitations WHERE id IN (
				    SELECT id FROM invitations WHERE expires_at <= :now ORDER BY expires_at LIMIT :limit
				    FOR UPDATE SKIP LOCKED)""").param("now", Timestamp.from(now)).param("limit", limit).update();
	}

	/**
	 * The emails a person has verified, through any provider account.
	 * @param userId the person
	 * @return the emails, lower case
	 */
	List<String> verifiedEmails(String userId) {
		return this.jdbc
			.sql("SELECT email FROM identities WHERE user_id = :user AND email_verified AND email IS NOT NULL")
			.param("user", userId)
			.query((rows, number) -> rows.getString("email").toLowerCase(Locale.ROOT))
			.list();
	}

	private static InvitationView view(ResultSet rows) throws SQLException {
		return new InvitationView(rows.getString("id"), rows.getString("workspace_id"), rows.getString("role"),
				rows.getString("email"), rows.getTimestamp("created_at").toInstant(),
				rows.getTimestamp("expires_at").toInstant(), instant(rows.getTimestamp("accepted_at")));
	}

	private static @Nullable Instant instant(@Nullable Timestamp time) {
		return (time != null) ? time.toInstant() : null;
	}

}
