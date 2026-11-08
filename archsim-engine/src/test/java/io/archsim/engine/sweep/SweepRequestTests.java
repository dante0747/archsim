package io.archsim.engine.sweep;

import org.junit.jupiter.api.Test;

import io.archsim.engine.simulation.SimulationLimits;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a sweep asks for: each wither changes its own part of the request and no other,
 * and the step's length and warm-up are held to what a step can run.
 */
class SweepRequestTests {

	private static final SimulationLimits LIMITS = new SimulationLimits(1_000, 60.0);

	private static final SweepRange RANGE = new SweepRange(50, 250);

	private static final SweepRequest COMPLETE = new SweepRequest("peak", RANGE, 5, 10, 2, LIMITS);

	@Test
	void of_aScenario_isEightStepsOfTwentySecondsAroundItsLoadWithinTheEnginesLimits() {
		SweepRequest request = SweepRequest.of("peak");

		assertThat(request).isEqualTo(new SweepRequest("peak", null, 8, 20.0, 5.0, SimulationLimits.DEFAULT));
	}

	@Test
	void withRange_aRequest_changesOnlyItsRange() {
		SweepRequest request = COMPLETE.withRange(new SweepRange(10, 20));

		assertThat(request).isEqualTo(new SweepRequest("peak", new SweepRange(10, 20), 5, 10, 2, LIMITS));
	}

	@Test
	void withSteps_aRequest_changesOnlyItsSteps() {
		SweepRequest request = COMPLETE.withSteps(6);

		assertThat(request).isEqualTo(new SweepRequest("peak", RANGE, 6, 10, 2, LIMITS));
	}

	@Test
	void withTiming_aRequest_changesOnlyItsStepsLengthAndWarmUp() {
		SweepRequest request = COMPLETE.withTiming(30, 4);

		assertThat(request).isEqualTo(new SweepRequest("peak", RANGE, 5, 30, 4, LIMITS));
	}

	@Test
	void withLimits_aRequest_changesOnlyItsLimits() {
		SweepRequest request = COMPLETE.withLimits(SimulationLimits.DEFAULT);

		assertThat(request).isEqualTo(new SweepRequest("peak", RANGE, 5, 10, 2, SimulationLimits.DEFAULT));
	}

	@Test
	void stepSeconds_aStepShorterThanTwoSeconds_isTwoSeconds() {
		SweepRequest request = COMPLETE.withTiming(1, 0);

		assertThat(request.stepSeconds()).isEqualTo(2.0);
	}

	@Test
	void warmupSeconds_aWarmUpLongerThanTheStep_isASecondShortOfIt() {
		SweepRequest request = COMPLETE.withTiming(1, 5);

		assertThat(request.warmupSeconds()).isEqualTo(1.0);
	}

	@Test
	void warmupSeconds_aWarmUpBelowZero_isNone() {
		SweepRequest request = COMPLETE.withTiming(10, -1);

		assertThat(request.warmupSeconds()).isZero();
	}

}
