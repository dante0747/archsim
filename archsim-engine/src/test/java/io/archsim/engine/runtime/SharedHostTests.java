package io.archsim.engine.runtime;

import org.junit.jupiter.api.Test;

import io.archsim.engine.host.HostMachine;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The design's host where the recorded runs do not reach it: a part's CPU ceiling with no
 * load seen, a ceiling of zero, and the clients' rate over the window. The expected
 * values are the Python engine's.
 */
class SharedHostTests {

	private static final HostMachine BUSY_HOST = new HostMachine(2, 2, 0.6, 0.85, 0.5, 0.3);

	private static final Timeline TIMELINE = new Timeline(10_000.0, 1_000.0, 9_000.0);

	private final SharedHost host = new SharedHost(new Simulation(), BUSY_HOST, TIMELINE, (seconds) -> 100.0);

	@Test
	void capacity_aHostThatSawNoLoad_isWhereThePartAloneFillsItsThreads() {
		double ceiling = this.host.capacity(200.0, 2.0, 0.0);

		assertThat(ceiling).isCloseTo(155.40000000000003, within(1e-9));
	}

	@Test
	void capacity_noCeilingAtFullSpeed_isThatCeiling() {
		double ceiling = this.host.capacity(0.0, 2.0, 50.0);

		assertThat(ceiling).isZero();
	}

	@Test
	void windowClientRps_aRampOverTheWindow_isItsMeanAtTheStepsMiddles() {
		SharedHost ramping = new SharedHost(new Simulation(), BUSY_HOST, TIMELINE, (seconds) -> seconds);

		double rps = ramping.windowClientRps();

		assertThat(rps).isEqualTo(5.0);
	}

}
