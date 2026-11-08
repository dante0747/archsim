package io.archsim.model.component.service;

/**
 * A runtime's JIT warm-up: how much slower a new instance runs at first, falling to full
 * speed over the warm-up time.
 *
 * @param factor the slowdown of a new instance, above 1
 * @param durationMs the time it takes to reach full speed, in milliseconds, above zero
 */
public record Warmup(double factor, double durationMs) {

	/**
	 * Checks that a new instance runs slower for some time.
	 */
	public Warmup {
		if (!(factor > 1) || !(durationMs > 0)) {
			throw new IllegalArgumentException(
					"a warm-up slows a new instance for some time; got " + factor + " for " + durationMs + " ms");
		}
	}

	/**
	 * How much slower an instance runs some time after it started: the factor at first,
	 * falling in a straight line to 1 at the end of the warm-up.
	 * @param elapsedMs the time since the instance started, in milliseconds
	 * @return the factor on its service time, 1 once it is warm
	 */
	public double slowdownAfter(double elapsedMs) {
		double left = 1.0 - elapsedMs / this.durationMs;
		return (left > 0) ? 1.0 + (this.factor - 1.0) * left : 1.0;
	}

}
