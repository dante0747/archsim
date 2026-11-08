package io.archsim.engine.runtime;

import org.junit.jupiter.api.Test;

import io.archsim.engine.host.HostMachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** An instance's own hyperthreads: the expected values are the Python engine's. */
class OwnHyperthreadsTests {

	private final OwnHyperthreads own = new OwnHyperthreads(HostMachine.ownHyperthreads(4.0, 0.6));

	@Test
	void capacity_aPartOnItsHyperthreads_isEveryThreadBusyAtTheSpeedOfAFullMachine() {
		double ceiling = this.own.capacity(300.0, 4.0, 120.0);

		assertThat(ceiling).isCloseTo(180.0, within(1e-9));
	}

	@Test
	void capacity_noCeilingAtFullSpeed_isThatCeiling() {
		double ceiling = this.own.capacity(0.0, 4.0, 120.0);

		assertThat(ceiling).isZero();
	}

}
