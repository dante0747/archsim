package io.archsim.app.limits;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

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
	 * Adds to several keys' counts, in a transaction of their own, unless that takes one
	 * of them over its limit: then none is added. Each is one upsert, so instances
	 * sharing the database count together.
	 * @param counts what to add, in the order the keys are locked
	 * @return the index in {@code counts} of the first count that would go over its
	 * limit, or empty when all were added
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	OptionalInt addAll(List<Count> counts) {
		for (int i = 0; i < counts.size(); i++) {
			if (!add(counts.get(i))) {
				// undoes the counts added before it; called through the proxy, so this
				// transaction is the method's own
				TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
				return OptionalInt.of(i);
			}
		}
		return OptionalInt.empty();
	}

	/**
	 * Adds to a key's count, unless that takes it over its limit.
	 * @param count what to add
	 * @return {@code true} when it was counted
	 */
	private boolean add(Count count) {
		// the upsert checks the limit only when it updates a row, so a first hit
		// larger than the limit would be stored
		if (count.cost() > count.limit()) {
			return false;
		}
		return this.jdbc.sql("""
				INSERT INTO rate_limits (key, window_start, count, expires_at)
				VALUES (:key, :start, :cost, :expires)
				ON CONFLICT (key, window_start) DO UPDATE SET count = rate_limits.count + :cost
				WHERE rate_limits.count + :cost <= :limit
				RETURNING count""")
			.param("key", Text.cut(count.key(), MAX_KEY))
			.param("start", count.window().start())
			.param("cost", count.cost())
			.param("expires", Timestamp.from(Instant.ofEpochSecond(count.window().end())))
			.param("limit", count.limit())
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

	/**
	 * What to add to one key's count.
	 *
	 * @param key what is counted
	 * @param window the window it is counted in
	 * @param cost how much to add
	 * @param limit the most the count may reach
	 */
	record Count(String key, Window window, int cost, int limit) {

	}

}
