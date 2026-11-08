package io.archsim.engine.components.service;

/**
 * A replica's arrival rate over about the last second: an exponentially weighted count of
 * its arrivals (time constant one second), corrected for the time it has been watched so
 * its first second does not read as a low rate.
 */
final class RateMeter {

	/** The time constant of the weighting, in milliseconds. */
	private static final double TRAILING_MS = 1000.0;

	private static final double MS_PER_SECOND = 1000.0;

	private final double start;

	private double since;

	/** Weighted arrivals per millisecond. */
	private double level;

	/**
	 * A meter that starts watching at a time.
	 * @param startMs when the replica started taking requests
	 */
	RateMeter(double startMs) {
		this.start = startMs;
		this.since = startMs;
	}

	/**
	 * Counts an arrival.
	 * @param now the arrival's time
	 */
	void arrive(double now) {
		decay(now);
		this.level += 1.0 / TRAILING_MS;
	}

	/**
	 * The arrivals per second over about the last second.
	 * @param now the time to read the rate at
	 * @return the rate; zero before any time has passed
	 */
	double rps(double now) {
		decay(now);
		double observed = 1.0 - StrictMath.exp(-Math.max(now - this.start, 0.0) / TRAILING_MS);
		if (observed <= 0) {
			return 0.0;
		}
		return this.level / observed * MS_PER_SECOND;
	}

	private void decay(double now) {
		if (now > this.since) {
			this.level *= StrictMath.exp(-(now - this.since) / TRAILING_MS);
			this.since = now;
		}
	}

}
