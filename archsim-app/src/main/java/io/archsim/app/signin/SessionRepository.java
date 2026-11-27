package io.archsim.app.signin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Browser sessions: the {@code sessions} table, keyed by the SHA-256 of each cookie.
 */
@Repository
class SessionRepository {

	private final JdbcClient jdbc;

	SessionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void add(NewSession session) {
		this.jdbc.sql("""
				INSERT INTO sessions (id, user_id, created_at, last_seen_at, expires_at, ip, user_agent)
				VALUES (:id, :user, :now, :now, :expires, :ip, :agent)""")
			.param("id", session.id())
			.param("user", session.userId())
			.param("now", Timestamp.from(session.createdAt()))
			.param("expires", Timestamp.from(session.expiresAt()))
			.param("ip", session.ip())
			.param("agent", session.userAgent())
			.update();
	}

	/**
	 * Whose a session is, while it lasts.
	 * @param id the session's id
	 * @param now the time
	 * @return the person's user id, if the session exists and has not expired
	 */
	Optional<String> userOf(String id, Instant now) {
		return this.jdbc.sql("SELECT user_id FROM sessions WHERE id = :id AND expires_at > :now")
			.param("id", id)
			.param("now", Timestamp.from(now))
			.query(String.class)
			.optional();
	}

	void delete(String id) {
		this.jdbc.sql("DELETE FROM sessions WHERE id = :id").param("id", id).update();
	}

	void deleteAllOf(String userId) {
		this.jdbc.sql("DELETE FROM sessions WHERE user_id = :user").param("user", userId).update();
	}

	/**
	 * Deletes some of the sessions that have expired, the first to expire first. Sessions
	 * another transaction holds are left to it.
	 * @param now the time
	 * @param limit how many to delete at most
	 * @return how many it deleted
	 */
	int deleteExpired(Instant now, int limit) {
		return this.jdbc.sql("""
				DELETE FROM sessions WHERE id IN (
				    SELECT id FROM sessions WHERE expires_at <= :now ORDER BY expires_at LIMIT :limit
				    FOR UPDATE SKIP LOCKED)""").param("now", Timestamp.from(now)).param("limit", limit).update();
	}

}
