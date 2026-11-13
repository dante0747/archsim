package io.archsim.benchmarks.experiments;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * An accuracy experiment: a stack, the traffic sent to it, and how it is calibrated.
 *
 * @param id its name, which names its results' folder
 * @param stack the stack it runs on ({@code compose.yaml}'s profile)
 * @param title what the comparison and the page call it
 * @param mix k6's traffic mix
 * @param readRatio the design's share of reads
 * @param keys the keys the traffic is spread over
 * @param skew the Zipf skew of that spread (0 for uniform)
 * @param calibration the mixes of the calibration passes
 * @param runtime the service's runtime ({@link Experiments#runtime(Experiment)}); a
 * chain's gateway and API are both Node.js
 * @param ttlS the cache's TTL, on the cache stacks
 * @param heldOut its registration when it is held out; {@code null} for the four the
 * model was built with
 */
public record Experiment(String id, String stack, String title, String mix, double readRatio, int keys, double skew,
		List<String> calibration, String runtime, int ttlS, @Nullable HeldOut heldOut) {

	public Experiment {
		calibration = List.copyOf(calibration);
	}

	/**
	 * Whether it is a held-out experiment, sealed until a frozen engine predicts it.
	 * @return whether it is held out
	 */
	public boolean isHeldOut() {
		return this.heldOut != null;
	}

}
