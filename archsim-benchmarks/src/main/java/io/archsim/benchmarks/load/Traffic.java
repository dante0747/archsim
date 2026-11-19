package io.archsim.benchmarks.load;

import io.archsim.benchmarks.docker.Stacks;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.engine.numeric.PythonRound;

/**
 * The traffic k6 sends: where, which mix of requests, how fast, and over which keys.
 *
 * @param target the service k6 calls, as the stack's network names it
 * @param mix the requests' mix ({@code read:0.9,write:0.1})
 * @param rate requests per second
 * @param keys the keys the requests are spread over
 * @param skew the keys' Zipf exponent, 0 for uniform
 */
public record Traffic(String target, String mix, double rate, int keys, double skew) {

	private static final double MS_PER_S = 1000;

	private static final int MS_DECIMALS = 3;

	/**
	 * An experiment's traffic at a rate: to the service under test, the first of its
	 * stack's containers.
	 * @param experiment the experiment
	 * @param mix the mix, the experiment's own or one of its calibration's
	 * @param rate requests per second
	 * @return the traffic
	 */
	public static Traffic of(Experiment experiment, String mix, double rate) {
		return new Traffic(target(experiment), mix, rate, experiment.keys(), experiment.skew());
	}

	/**
	 * The virtual users k6 holds ready for this traffic, all of them before the first
	 * request and through the warm-up and the measured window alike: as many as a whole
	 * timeout's arrivals up to 1,000, as many as a quarter of a second's above that
	 * ({@link Protocol#GENERATOR_COVER_S}), and fifty more; 3,000 at most.
	 * @return the virtual users
	 */
	public long virtualUsers() {
		long cover = (long) Math.ceil(this.rate * Protocol.GENERATOR_COVER_S);
		long wholeTimeout = (long) Math.ceil(this.rate * Protocol.TIMEOUT_MS / MS_PER_S);
		long waiting = Math.max(cover, Math.min(wholeTimeout, Protocol.GENERATOR_TIMEOUT_USERS));
		return Math.min(waiting + Protocol.GENERATOR_SPARE_USERS, Protocol.GENERATOR_MOST_USERS);
	}

	/**
	 * The seconds of this traffic's arrivals that may wait for their answers at once
	 * before k6 has no virtual user free and drops a request: the timeout at most.
	 * @return the seconds, to the millisecond
	 */
	public double coveredSeconds() {
		double seconds = (virtualUsers() - Protocol.GENERATOR_SPARE_USERS) / this.rate;
		return PythonRound.round(Math.min(Protocol.TIMEOUT_MS / MS_PER_S, seconds), MS_DECIMALS);
	}

	/**
	 * The service k6 calls on an experiment's stack.
	 * @param experiment the experiment
	 * @return its URL inside the stack's network
	 */
	public static String target(Experiment experiment) {
		return "http://" + Stacks.standard().containers(experiment.stack()).getFirst() + ":8080";
	}

}
