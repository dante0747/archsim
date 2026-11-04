package io.archsim.engine.queueing;

/**
 * The first two moments of a time.
 *
 * @param mean the mean, in milliseconds
 * @param variance the variance, in square milliseconds
 */
public record Moments(double mean, double variance) {

	/** A time that is always zero. */
	public static final Moments ZERO = new Moments(0.0, 0.0);

}
