package io.archsim.app.limits;

import java.time.Clock;
import java.util.List;
import java.util.OptionalInt;

import org.springframework.stereotype.Component;

import io.archsim.app.config.ArchsimProperties;
import io.archsim.app.web.ApiException;

/**
 * Rate limits every API instance shares: one counter per key ({@code ws:<id>:runs},
 * {@code user:<id>:tokens}, {@code ip:<address>:login}...) and fixed window, in the
 * {@code rate_limits} table. A hit is counted in a transaction of its own, so it counts
 * even if the request then fails, and only while the count stays within the limit, so a
 * refused request costs nothing; hits against several keys are counted together or not at
 * all. Off without limits ({@code ARCHSIM_RATE_LIMITS}).
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
		enforceAll(List.of(new Charge(key, limit, message)), 1);
	}

	/**
	 * Counts some hits against several keys at once, all or none: when one key is over
	 * its limit, no key is counted. The keys' counters are locked in the order given, so
	 * callers that charge the same keys must list them in the same order (a person's
	 * before a workspace's): two requests locking them in opposite orders could deadlock.
	 * @param charges the keys, in that order
	 * @param cost how many hits against each
	 * @throws ApiException {@code 429} with {@code Retry-After}, in the words of the
	 * first key the hits do not fit and for when its window ends
	 */
	public void enforceAll(List<Charge> charges, int cost) {
		if (!this.properties.limitsOn()) {
			return;
		}
		double now = this.clock.millis() / MILLIS;
		List<RateLimitRepository.Count> counts = charges.stream().map((charge) -> count(charge, now, cost)).toList();
		OptionalInt refused = this.repository.addAll(counts);
		if (refused.isPresent()) {
			int index = refused.getAsInt();
			long retry = Math.max(1, (long) Math.ceil(counts.get(index).window().end() - now));
			throw ApiException.tooManyRequests(charges.get(index).message() + " Try again in " + waitText(retry) + ".",
					retry);
		}
	}

	/**
	 * Returns what to add to a key's count in the window that holds this moment.
	 * @param charge the key and its limit
	 * @param now this moment, in seconds since the epoch
	 * @param cost how many hits
	 * @return the count
	 */
	private static RateLimitRepository.Count count(Charge charge, double now, int cost) {
		long length = charge.limit().windowSeconds();
		long start = (long) Math.floor(now / length) * length;
		return new RateLimitRepository.Count(charge.key(), new RateLimitRepository.Window(start, start + length), cost,
				charge.limit().limit());
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

	/**
	 * Hits to count against one key.
	 *
	 * @param key what is counted, such as {@code user:<id>:runs}
	 * @param limit how many the key may do per window
	 * @param message what a refusal says, such as "You have started 300 runs this hour."
	 */
	public record Charge(String key, RateLimit limit, String message) {

	}

}
