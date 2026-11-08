package io.archsim.engine.simulator;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.archsim.engine.run.SimulationLimitException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A run's wall clock against its limit, read from a clock each look advances by a fixed
 * step.
 */
class WallClockTests {

	private static final long NANOS_PER_SECOND = 1_000_000_000L;

	private final AtomicLong nanos = new AtomicLong();

	@Test
	void check_exactlyAtTheLimit_letsTheRunGoOn() {
		WallClock wallClock = new WallClock(() -> this.nanos.getAndAdd(NANOS_PER_SECOND), 1.0);

		assertThatCode(() -> wallClock.check(1_234)).doesNotThrowAnyException();
	}

	@Test
	void check_pastALimitWithAFraction_writesTheLimitWithoutDecimals() {
		WallClock wallClock = new WallClock(() -> this.nanos.getAndAdd(3 * NANOS_PER_SECOND), 2.5);

		assertThatExceptionOfType(SimulationLimitException.class).isThrownBy(() -> wallClock.check(1_234))
			.withMessage("Simulation exceeded the wall-clock limit of 2 s at simulated time 1.2 s. "
					+ "Reduce RPS, duration, fan-out or model size.");
	}

}
