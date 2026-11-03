package io.archsim.app.tokens;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code api_tokens} table.
 */
@Repository
class TokenRepository {

	private static final String COLUMNS = "id, user_id, name, prefix, scope, workspace_id, created_at, last_used_at, "
			+ "expires_at, revoked_at";

	private final JdbcClient jdbc;

	TokenRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * A person's tokens that still work.
	 * @param userId the person
	 * @param now the time
	 * @return the tokens, newest first
	 */
	List<TokenView> active(String userId, Instant now) {
		return this.jdbc
			.sql("SELECT " + COLUMNS + " FROM api_tokens WHERE user_id = :user AND revoked_at IS NULL "
					+ "AND (expires_at IS NULL OR expires_at > :now) ORDER BY created_at DESC")
			.param("user", userId)
			.param("now", Timestamp.from(now))
			.query((rows, number) -> view(rows))
			.list();
	}

	/**
	 * Whose a token is.
	 * @param id the token
	 * @return the person's user id, if the token exists
	 */
	Optional<String> ownerOf(String id) {
		return this.jdbc.sql("SELECT user_id FROM api_tokens WHERE id = :id")
			.param("id", id)
			.query(String.class)
			.optional();
	}

	void add(TokenView token, String userId, String hash) {
		this.jdbc.sql("""
				INSERT INTO api_tokens (id, user_id, workspace_id, name, prefix, token_hash, scope, created_at,
				                        expires_at)
				VALUES (:id, :user, :workspace, :name, :prefix, :hash, :scope, :created, :expires)""")
			.param("id", token.id())
			.param("user", userId)
			.param("workspace", token.workspaceId())
			.param("name", token.name())
			.param("prefix", token.prefix())
			.param("hash", hash)
			.param("scope", token.scope())
			.param("created", Timestamp.from(token.createdAt()))
			.param("expires", timestamp(token.expiresAt()))
			.update();
	}

	/**
	 * Revokes a token unless it was revoked already.
	 * @param id the token
	 * @param now the time
	 */
	void revoke(String id, Instant now) {
		this.jdbc.sql("UPDATE api_tokens SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
			.param("now", Timestamp.from(now))
			.param("id", id)
			.update();
	}

	private static TokenView view(ResultSet rows) throws SQLException {
		return new TokenView(rows.getString("id"), rows.getString("name"), rows.getString("prefix"),
				rows.getString("scope"), rows.getString("workspace_id"), rows.getTimestamp("created_at").toInstant(),
				instant(rows.getTimestamp("last_used_at")), instant(rows.getTimestamp("expires_at")),
				instant(rows.getTimestamp("revoked_at")));
	}

	private static @Nullable Instant instant(@Nullable Timestamp time) {
		return (time != null) ? time.toInstant() : null;
	}

	private static @Nullable Timestamp timestamp(@Nullable Instant time) {
		return (time != null) ? Timestamp.from(time) : null;
	}

}
