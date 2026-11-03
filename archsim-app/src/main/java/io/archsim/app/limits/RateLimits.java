package io.archsim.app.limits;

import java.time.Clock;

import org.springframework.stereotype.Component;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.web.ApiException;

/**
 * Rate limits every API instance shares: one counter per key ({@code ws:<id>:runs},
 * {@code user:<id>:tokens}, {@code ip:<address>:login}...) and fixed window, in the
 * {@code rate_limits} table. A hit is counted in a transaction of its own, so it counts
 * even if the request then fails, and only while the count stays within the limit, so a
 * refused request costs nothing. Off without limits ({@code ARCHSIM_RATE_LIMITS}).
 */
@Component
public class RateLimits {

	private static final int MINUTE = 60;

	/** Waits shorter than this are told in seconds. */
	private static final int SECONDS_SHOWN = 90;

	/** Waits shorter than this are told in minutes, longer ones in hours. */
	private static final int MINUTES_SHOWN = 5400;

	private static final double MILLIS = 1000.0;

	private final RateLimitRepository repository;

	private final ArchsimProperties properties;

	private final Clock clock;

	RateLimits(RateLimitRepository repository, ArchsimProperties properties, Clock clock) {
		this.repository = repository;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Counts one hit against a key.
	 * @param key what is counted, such as {@code user:<id>:tokens}
	 * @param limit how many the key may do per window
	 * @param message what was refused, such as "You have made many tokens today."
	 * @throws ApiException {@code 429} with {@code Retry-After} when the key is over its
	 * limit
	 */
	public void enforce(String key, RateLimit limit, String message) {
		enforce(key, limit, message, 1);
	}

	/**
	 * Counts some hits against a key at once, all or none.
	 * @param key what is counted
	 * @param limit how many the key may do per window
	 * @param message what was refused
	 * @param cost how many hits
	 * @throws ApiException {@code 429} with {@code Retry-After} when they do not fit
	 */
	public void enforce(String key, RateLimit limit, String message, int cost) {
		if (!this.properties.limitsOn()) {
			return;
		}
		double now = this.clock.millis() / MILLIS;
		long start = (long) Math.floor(now / limit.windowSeconds()) * limit.windowSeconds();
		long retry = Math.max(1, (long) Math.ceil(start + limit.windowSeconds() - now));
		boolean counted = cost <= limit.limit() && this.repository.add(key,
				new RateLimitRepository.Window(start, start + limit.windowSeconds()), cost, limit.limit());
		if (!counted) {
			throw ApiException.tooManyRequests(message + " Try again in " + waitText(retry) + ".", retry);
		}
	}

	/**
	 * A wait, as people read it.
	 * @param seconds the wait
	 * @return seconds under a minute and a half, minutes under an hour and a half, else
	 * hours, rounded up
	 */
	static String waitText(long seconds) {
		if (seconds < SECONDS_SHOWN) {
			return seconds + " s";
		}
		if (seconds < MINUTES_SHOWN) {
			return Math.ceilDiv(seconds, MINUTE) + " min";
		}
		return Math.ceilDiv(seconds, RateLimit.HOUR) + " h";
	}

}
