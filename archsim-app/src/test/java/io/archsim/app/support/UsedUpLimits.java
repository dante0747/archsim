package io.archsim.app.support;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;

import io.archsim.app.limits.RateLimit;

/**
 * Rate limits used up before a test asks for one more.
 */
public final class UsedUpLimits {

	/** How many windows the seeded counts last for. */
	private static final int WINDOWS_KEPT = 3;

	private UsedUpLimits() {
	}

	/**
	 * Uses up a rate limit for a key: in this window and the next one, so a test running
	 * at the turn of a window still finds it used up.
	 * @param jdbc the test database
	 * @param key what the limit counts, such as {@code user:<id>:comments}
	 * @param limit the limit
	 */
	public static void useUp(JdbcClient jdbc, String key, RateLimit limit) {
		Instant now = Instant.now();
		long start = now.getEpochSecond() / limit.windowSeconds() * limit.windowSeconds();
		for (long window : new long[] { start, start + limit.windowSeconds() }) {
			jdbc.sql("INSERT INTO rate_limits (key, window_start, count, expires_at) "
					+ "VALUES (:key, :window, :count, :expires)")
				.param("key", key)
				.param("window", window)
				.param("count", limit.limit())
				.param("expires", Timestamp.from(now.plusSeconds(WINDOWS_KEPT * limit.windowSeconds())))
				.update();
		}
	}

}
