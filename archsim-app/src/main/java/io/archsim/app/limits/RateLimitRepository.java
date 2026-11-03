package io.archsim.app.limits;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.archsim.app.store.Text;

/**
 * The {@code rate_limits} counters.
 */
@Repository
class RateLimitRepository {

	/** The longest key the table holds. */
	private static final int MAX_KEY = 200;

	private final JdbcClient jdbc;

	RateLimitRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Adds to a key's count in a window, in a transaction of its own, unless that takes
	 * it over the limit: one upsert, so instances sharing the database count together.
	 * @param key what is counted
	 * @param window the window it is counted in
	 * @param cost how much to add
	 * @param limit the most the count may reach
	 * @return {@code true} when it was counted
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	boolean add(String key, Window window, int cost, int limit) {
		return this.jdbc.sql("""
				INSERT INTO rate_limits (key, window_start, count, expires_at)
				VALUES (:key, :start, :cost, :expires)
				ON CONFLICT (key, window_start) DO UPDATE SET count = rate_limits.count + :cost
				WHERE rate_limits.count + :cost <= :limit
				RETURNING count""")
			.param("key", Text.cut(key, MAX_KEY))
			.param("start", window.start())
			.param("cost", cost)
			.param("expires", Timestamp.from(Instant.ofEpochSecond(window.end())))
			.param("limit", limit)
			.query(Integer.class)
			.optional()
			.isPresent();
	}

	/**
	 * A fixed window of time a count belongs to.
	 *
	 * @param start its start, in seconds since the epoch
	 * @param end its end, in seconds since the epoch
	 */
	record Window(long start, long end) {

	}

}
