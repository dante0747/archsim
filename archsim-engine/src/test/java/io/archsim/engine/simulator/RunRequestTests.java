package io.archsim.engine.simulator;

import org.junit.jupiter.api.Test;

import io.archsim.engine.simulation.SimulationLimits;

import static org.assertj.core.api.Assertions.assertThat;

/** What a run asks for: each wither changes its own part of the request and no other. */
class RunRequestTests {

	private static final SimulationLimits LIMITS = new SimulationLimits(1_000, 60.0);

	private static final RunRequest COMPLETE = new RunRequest("peak", 7L, SimulationMode.SAMPLED, LIMITS);

	@Test
	void of_aScenario_isTheWholeRunWithTheWorkloadsSeedWithinTheEnginesLimits() {
		RunRequest request = RunRequest.of("peak");

		assertThat(request).isEqualTo(new RunRequest("peak", null, SimulationMode.FULL, SimulationLimits.DEFAULT));
	}

	@Test
	void withSeed_aRequest_changesOnlyItsSeed() {
		RunRequest request = COMPLETE.withSeed(11);

		assertThat(request).isEqualTo(new RunRequest("peak", 11L, SimulationMode.SAMPLED, LIMITS));
	}

	@Test
	void withMode_aRequest_changesOnlyItsMode() {
		RunRequest request = COMPLETE.withMode(SimulationMode.FULL);

		assertThat(request).isEqualTo(new RunRequest("peak", 7L, SimulationMode.FULL, LIMITS));
	}

	@Test
	void withLimits_aRequest_changesOnlyItsLimits() {
		RunRequest request = COMPLETE.withLimits(SimulationLimits.DEFAULT);

		assertThat(request).isEqualTo(new RunRequest("peak", 7L, SimulationMode.SAMPLED, SimulationLimits.DEFAULT));
	}

}
