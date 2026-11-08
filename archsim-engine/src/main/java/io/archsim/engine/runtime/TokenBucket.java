package io.archsim.engine.runtime;

import org.jspecify.annotations.Nullable;

/**
 * A token bucket, for rate limits and provisioned throughput: {@code ratePerSecond}
 * tokens a second, at most {@code burst} held, full at the start. Time is in
 * milliseconds.
 */
public final class TokenBucket {

	private static final double MS_PER_SECOND = 1000.0;

	private final double ratePerMs;

	private final double burst;

	private double tokens;

	private double lastMs;

	private TokenBucket(double ratePerSecond, double burst) {
		if (ratePerSecond <= 0) {
			throw new IllegalArgumentException("a token bucket's rate must be above 0; got " + ratePerSecond);
		}
		this.ratePerMs = ratePerSecond / MS_PER_SECOND;
		this.burst = burst;
		this.tokens = burst;
	}

	/**
	 * A full bucket that holds a second's tokens, at least one.
	 * @param ratePerSecond tokens added a second, above 0
	 * @return the bucket
	 * @throws IllegalArgumentException for a rate of 0 or less
	 */
	public static TokenBucket ofRate(double ratePerSecond) {
		return of(ratePerSecond, null);
	}

	/**
	 * A full bucket.
	 * @param ratePerSecond tokens added a second, above 0
	 * @param burst the most it holds; {@code null} for a second's tokens, at least one
	 * @return the bucket
	 * @throws IllegalArgumentException for a rate of 0 or less
	 */
	public static TokenBucket of(double ratePerSecond, @Nullable Double burst) {
		return new TokenBucket(ratePerSecond, (burst != null) ? burst : Math.max(1.0, ratePerSecond));
	}

	/**
	 * Takes tokens if the bucket holds enough.
	 * @param nowMs the time
	 * @param count how many
	 * @return {@code true} when taken; {@code false} (and nothing taken) when too few
	 */
	public boolean tryTake(double nowMs, double count) {
		double elapsed = nowMs - this.lastMs;
		if (elapsed > 0) {
			this.tokens = Math.min(this.burst, this.tokens + elapsed * this.ratePerMs);
			this.lastMs = nowMs;
		}
		if (this.tokens >= count) {
			this.tokens -= count;
			return true;
		}
		return false;
	}

}
