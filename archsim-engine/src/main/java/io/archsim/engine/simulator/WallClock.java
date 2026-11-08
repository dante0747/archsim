package io.archsim.engine.simulator;

import java.util.function.LongSupplier;

import io.archsim.engine.run.SimulationLimitException;
import io.archsim.model.text.PythonFormat;

/**
 * The wall-clock time a run has taken since it started, against the most it may take.
 */
final class WallClock {

	private static final double NANOS_PER_SECOND = 1e9;

	private static final double MS_PER_SECOND = 1000.0;

	private final LongSupplier clock;

	private final long started;

	private final double limitS;

	/**
	 * A run's clock, started now.
	 * @param clock the wall clock, in nanoseconds, as {@link System#nanoTime()} reads it
	 * @param limitS the most seconds the run may take
	 */
	WallClock(LongSupplier clock, double limitS) {
		this.clock = clock;
		this.started = clock.getAsLong();
		this.limitS = limitS;
	}

	/**
	 * The time the run has taken so far.
	 * @return seconds
	 */
	double elapsedS() {
		return (this.clock.getAsLong() - this.started) / NANOS_PER_SECOND;
	}

	/**
	 * Stops a run that has taken longer than it may.
	 * @param simulatedMs the simulated time it has reached
	 * @throws SimulationLimitException when it is past its limit, in the Python engine's
	 * words
	 */
	void check(double simulatedMs) {
		if (elapsedS() > this.limitS) {
			throw new SimulationLimitException(
					"Simulation exceeded the wall-clock limit of " + PythonFormat.format(this.limitS, ".0f")
							+ " s at simulated time " + PythonFormat.format(simulatedMs / MS_PER_SECOND, ".1f")
							+ " s. Reduce RPS, duration, fan-out or model size.");
		}
	}

}
