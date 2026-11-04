package io.archsim.engine.queueing;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * How long an arrival waits in an M/M/c queue: the chance that it waits at all, and the
 * moments of its wait.
 *
 * @param probability the chance to wait
 * @param moments the wait's mean and variance, counting the arrivals that do not wait
 */
public record QueueWait(double probability, Moments moments) {

	private static final double MILLIS_PER_SECOND = 1000.0;

	/**
	 * The wait in an M/M/c queue.
	 * @param servers how many arrivals are served at once
	 * @param arrivalsPerSecond the arrival rate
	 * @param holdMs the mean time a server holds an arrival, in milliseconds
	 * @return the wait, never waiting when there are no arrivals or holds; none when the
	 * queue is unstable
	 */
	public static Optional<QueueWait> mmc(int servers, double arrivalsPerSecond, double holdMs) {
		if (arrivalsPerSecond <= 0 || holdMs <= 0) {
			return Optional.of(new QueueWait(0.0, Moments.ZERO));
		}
		double servedPerSecond = MILLIS_PER_SECOND / holdMs;
		OptionalDouble waits = ErlangC.waitProbability(servers, arrivalsPerSecond / servedPerSecond);
		if (waits.isEmpty()) {
			return Optional.empty();
		}
		double probability = waits.getAsDouble();
		double drainPerSecond = servers * servedPerSecond - arrivalsPerSecond;
		double mean = probability / drainPerSecond * MILLIS_PER_SECOND;
		double secondMoment = 2.0 * probability / (drainPerSecond * drainPerSecond)
				* (MILLIS_PER_SECOND * MILLIS_PER_SECOND);
		return Optional.of(new QueueWait(probability, new Moments(mean, Math.max(0.0, secondMoment - mean * mean))));
	}

}
