package io.archsim.model.distribution;

import io.archsim.model.codec.Tagged;

/**
 * A service-time or latency distribution, in milliseconds, told apart in JSON by its
 * {@code kind}: {@code constant}, {@code exponential}, {@code uniform}, {@code lognormal}
 * or {@code empirical}. Sampling it is the simulator's job; the model knows its shape,
 * mean and description.
 */
@Tagged("kind")
public sealed interface Distribution permits Constant, Exponential, Uniform, LogNormal, Empirical {

	/** The most any sample may be (10 minutes), so heavy tails cannot stall a run. */
	double MAX_SAMPLE_MS = 600_000.0;

	/**
	 * The analytic mean in milliseconds, before samples are capped at
	 * {@link #MAX_SAMPLE_MS}.
	 */
	double expected();

	/** A short description, such as {@code lognormal, p50 8 ms / p95 25 ms}. */
	String describe();

}
