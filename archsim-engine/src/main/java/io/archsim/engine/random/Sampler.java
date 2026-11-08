package io.archsim.engine.random;

import io.archsim.model.distribution.Distribution;

/**
 * Draws values of a service-time or latency distribution, in milliseconds, as the Python
 * engine samples it: the same draws from the stream, and no value above
 * {@link Distribution#MAX_SAMPLE_MS} where a tail is unbounded.
 */
@FunctionalInterface
public interface Sampler {

	/**
	 * The next value.
	 * @param random the stream to draw from
	 * @return the value, in milliseconds
	 */
	double sample(PythonRandom random);

	/**
	 * The sampler of a distribution. A constant draws nothing, and nor does a lognormal
	 * whose p95 is its median.
	 * @param distribution the distribution
	 * @return its sampler
	 */
	static Sampler of(Distribution distribution) {
		return Samplers.of(distribution);
	}

}
