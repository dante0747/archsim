package io.archsim.app.access;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Sessions, API tokens and accounts, as signing a request in reads them.
 */
@Repository
class CredentialRepository {

	private final JdbcClient jdbc;

	CredentialRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * A session, with whether its account is a guest's: one that expires.
	 * @param id the session's id
	 * @return the session, if it exists
	 */
	Optional<StoredSession> session(String id) {
		return this.jdbc.sql("""
				SELECT s.id, s.user_id, s.expires_at, s.last_seen_at, u.expires_at IS NOT NULL AS guest
				FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.id = :id""")
			.param("id", id)
			.query((rows, number) -> new StoredSession(rows.getString("id"), rows.getString("user_id"),
					rows.getTimestamp("expires_at").toInstant(), rows.getTimestamp("last_seen_at").toInstant(),
					rows.getBoolean("guest")))
			.optional();
	}

	Optional<StoredToken> token(String hash) {
		return this.jdbc.sql("""
				SELECT id, user_id, workspace_id, scope, expires_at, revoked_at, last_used_at
				FROM api_tokens WHERE token_hash = :hash""")
			.param("hash", hash)
			.query((rows, number) -> new StoredToken(rows.getString("id"), rows.getString("user_id"),
					rows.getString("workspace_id"), rows.getString("scope"), instant(rows.getTimestamp("expires_at")),
					instant(rows.getTimestamp("revoked_at")), instant(rows.getTimestamp("last_used_at"))))
			.optional();
	}

	/**
	 * Whether an account may sign in: it exists and is not disabled.
	 * @param userId the account
	 * @return {@code true} for an active account
	 */
	boolean active(String userId) {
		return this.jdbc.sql("SELECT count(*) FROM users WHERE id = :id AND disabled_at IS NULL")
			.param("id", userId)
			.query(Integer.class)
			.single() > 0;
	}

	/**
	 * Marks a session, and its account, seen now.
	 * @param session the session
	 * @param now the time
	 */
	void touch(StoredSession session, Instant now) {
		Timestamp seen = Timestamp.from(now);
		this.jdbc.sql("UPDATE sessions SET last_seen_at = :now WHERE id = :id")
			.param("now", seen)
			.param("id", session.id())
			.update();
		this.jdbc.sql("UPDATE users SET last_seen_at = :now WHERE id = :id")
			.param("now", seen)
			.param("id", session.userId())
			.update();
	}

	void touch(StoredToken token, Instant now) {
		this.jdbc.sql("UPDATE api_tokens SET last_used_at = :now WHERE id = :id")
			.param("now", Timestamp.from(now))
			.param("id", token.id())
			.update();
	}

	private static @Nullable Instant instant(@Nullable Timestamp time) {
		return (time != null) ? time.toInstant() : null;
	}

}
