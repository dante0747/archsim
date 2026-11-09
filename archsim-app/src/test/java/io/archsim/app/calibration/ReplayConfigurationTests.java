package io.archsim.app.calibration;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.Simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * A calibration's replay as the application wires it: on the application's simulator,
 * through the pool of replay threads.
 */
class ReplayConfigurationTests {

	private static final ObjectNode DESIGN = JsonNodeFactory.instance.objectNode().put("kind", "archsim.architecture");

	private static final SimulationLimits LIMITS = new SimulationLimits(2_000_000, 20.0);

	@Test
	void replaySimulator_aDesign_isReplayedWholeOnTheSimulatorGiven() {
		Simulator simulator = mock();
		ObjectNode report = JsonNodeFactory.instance.objectNode().put("replayed", true);
		given(simulator.run(DESIGN, RunRequest.of(null).withLimits(LIMITS))).willReturn(report);

		try (ReplayPool replay = new ReplayConfiguration().replaySimulator(simulator)) {
			assertThat(replay.simulate(DESIGN, LIMITS)).isSameAs(report);
		}
	}

}
