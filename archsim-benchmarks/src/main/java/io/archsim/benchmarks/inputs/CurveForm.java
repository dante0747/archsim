package io.archsim.benchmarks.inputs;

/**
 * How the service's CPU per request changes with load, below and above the calibration
 * staircase's top rate. run.py's {@code CURVE_FORM} had the flat and the log forms; the
 * law is new in Java.
 *
 * <p>
 * The derivation takes the {@link #LAW} (ADR-0093): above the staircase the measured cost
 * per request kept falling, and of the forms tried on the kept runs the law came nearest
 * to it there without promising capacity a short staircase cannot show. {@link #FLAT} is
 * how every recorded input and development check up to then was derived, which the parity
 * tests derive again. {@link #LOG} is W26's extension, which stayed off: re-simulated
 * in-sample it moved cache-aside's knee error from -25% to -23% and nothing else.
 */
enum CurveForm {

	/**
	 * Each staircase rate's cost fitted as a law of the rate, carried above the top rate
	 * to where the service's CPUs fill, but no further than twice the top rate
	 * (ADR-0093).
	 */
	LAW,

	/**
	 * The engine's never-rising fit of each rate, flat above the top rate: engine
	 * 0.15.0's rule.
	 */
	FLAT,

	/**
	 * W26's extension: the upper staircase's fall carried on linearly in log(rate), at
	 * most one measured stretch, up to the service's CPUs' capacity.
	 */
	LOG

}
