package io.archsim.app.signin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sandboxes' guests: the accounts of the {@code users} table that expire.
 */
@Repository
class GuestRepository {

	/** The advisory lock admissions take turns on: "archsim" and 3. */
	private static final long ADMISSION_LOCK = 0x6172_6368_7369_6D03L;

	/** The name a guest goes by. */
	private static final String NAME = "Guest";

	private final JdbcClient jdbc;

	GuestRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Waits until no other admission runs, and keeps the others waiting until this
	 * transaction ends: an admission counts the guests and adds one, so two at once
	 * cannot both take the last place.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	void takeTurn() {
		this.jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) AS turn")
			.param("key", ADMISSION_LOCK)
			.query(Integer.class)
			.single();
	}

	/**
	 * How many guests there are: those whose sandboxes have expired but are not yet
	 * deleted count too, as their rows are still there.
	 * @return the number of guests
	 */
	int count() {
		return this.jdbc.sql("SELECT count(*) FROM users WHERE expires_at IS NOT NULL").query(Integer.class).single();
	}

	/**
	 * When the first guest expires.
	 * @return the earliest expiry, or empty when there are no guests
	 */
	Optional<Instant> firstExpiry() {
		return this.jdbc.sql("SELECT min(expires_at) FROM users WHERE expires_at IS NOT NULL")
			.query((rows, number) -> Optional.ofNullable(rows.getTimestamp(1)).map(Timestamp::toInstant))
			.single();
	}

	/**
	 * Stores a guest: an account with a name, an expiry, and no identity or email.
	 * @param guest the guest
	 * @param now when they were admitted
	 */
	void add(Guest guest, Instant now) {
		this.jdbc.sql("INSERT INTO users (id, name, created_at, expires_at) VALUES (:id, :name, :now, :expires)")
			.param("id", guest.id())
			.param("name", NAME)
			.param("now", Timestamp.from(now))
			.param("expires", Timestamp.from(guest.expiresAt()))
			.update();
	}

	/**
	 * The guests whose sandboxes have expired.
	 * @param now the time
	 * @return their user ids, the first to expire first
	 */
	List<String> expired(Instant now) {
		return this.jdbc.sql("SELECT id FROM users WHERE expires_at <= :now ORDER BY expires_at, id")
			.param("now", Timestamp.from(now))
			.query((rows, number) -> rows.getString("id"))
			.list();
	}

	/**
	 * Deletes a guest, and with them their sessions and memberships. Someone else's
	 * account is never deleted here.
	 * @param userId the guest
	 */
	void delete(String userId) {
		this.jdbc.sql("DELETE FROM users WHERE id = :id AND expires_at IS NOT NULL").param("id", userId).update();
	}

}
