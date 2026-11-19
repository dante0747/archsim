package io.archsim.benchmarks.baselines;

import io.archsim.engine.numeric.PythonRound;

/**
 * The units and the precision of the figures a baselines file records: a load to one
 * decimal, as a comparison's knee, and a time, a share or a fitted coefficient to six, so
 * that a reader who divides the recorded figures finds the recorded load.
 */
final class Units {

	/**
	 * The milliseconds of a second: a capacity is what a part has over the time taken.
	 */
	static final double MS_PER_SECOND = 1000.0;

	private static final int RATE_DECIMALS = 1;

	private static final int PRECISE_DECIMALS = 6;

	private Units() {
	}

	/**
	 * A load as the file records it.
	 * @param requestsPerSecond the load
	 * @return the load, to one decimal
	 */
	static double rate(double requestsPerSecond) {
		return PythonRound.round(requestsPerSecond, RATE_DECIMALS);
	}

	/**
	 * A time, a share or a fitted coefficient as the file records it.
	 * @param value the figure
	 * @return the figure, to six decimals
	 */
	static double precise(double value) {
		return PythonRound.round(value, PRECISE_DECIMALS);
	}

}
