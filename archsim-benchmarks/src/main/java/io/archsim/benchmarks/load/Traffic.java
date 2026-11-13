package io.archsim.benchmarks.load;

import io.archsim.benchmarks.docker.Stacks;
import io.archsim.benchmarks.experiments.Experiment;

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
	 * The service k6 calls on an experiment's stack.
	 * @param experiment the experiment
	 * @return its URL inside the stack's network
	 */
	public static String target(Experiment experiment) {
		return "http://" + Stacks.standard().containers(experiment.stack()).getFirst() + ":8080";
	}

}
