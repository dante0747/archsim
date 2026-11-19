package io.archsim.engine.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The CPU of a design's host machine: the expected values are the Python engine's. */
class HostMachineTests {

	/**
	 * Four hyperthreaded cores, a falling all-core clock, a background and a load
	 * generator.
	 */
	private static final HostMachine LAPTOP = new HostMachine(4, 2, 0.6, 0.8, 0.5, 0.2);

	@ParameterizedTest(name = "with {0} busy threads a thread runs at {1}")
	@CsvSource({ "1, 1.0", "3, 0.9428571428571428", "6, 0.6285714285714287", "12, 0.31999999999999995" })
	void speed_moreBusyThreads_slowEachDown(double busy, double speed) {
		assertThat(LAPTOP.speed(busy)).isCloseTo(speed, within(1e-15));
	}

	@Test
	void speedWithEveryThreadBusy_aHost_isItsAllCoreClockTimesItsSiblingSpeed() {
		assertThat(LAPTOP.speedWithEveryThreadBusy()).isCloseTo(0.8 * 0.6, within(1e-15));
	}

	@ParameterizedTest(name = "at a sibling speed of {0}")
	@ValueSource(doubles = { 0.3, 0.4, 0.5, 0.6, 0.8 })
	void speedWithEveryThreadBusy_aPartsOwnHyperthreads_isTheirSiblingSpeed(double siblingSpeed) {
		HostMachine own = HostMachine.ownHyperthreads(8.0, siblingSpeed);

		assertThat(own.speedWithEveryThreadBusy()).isCloseTo(siblingSpeed, within(1e-15));
	}

	@Test
	void other_theClientsTraffic_addsToTheBackground() {
		assertThat(LAPTOP.other(1000)).isCloseTo(0.7, within(1e-15));
	}

	@Test
	void solve_someWork_isTheBusyThreadsAtTheirOwnSpeed() {
		HostLoad load = LAPTOP.solve(2, 100);

		assertThat(load.busyThreads()).isCloseTo(2.6168673214090523, within(1e-9));
		assertThat(load.speed()).isCloseTo(0.9538037908168843, within(1e-9));
	}

	@Test
	void solve_noWork_isTheBackgroundAtFullSpeed() {
		assertThat(LAPTOP.solve(0, 1000)).isEqualTo(new HostLoad(0.7, 1.0));
	}

	@Test
	void solve_moreWorkThanTheMachineDoes_capsTheBusyThreads() {
		HostLoad load = LAPTOP.solve(6, 1000);

		assertThat(load.busyThreads()).isCloseTo(518.7, within(1e-9));
		assertThat(load.speed()).isCloseTo(0.007403123192596876, within(1e-15));
	}

	@Test
	void scaleFilling_aPartOnABusyHost_fillsAtTheMultiplePythonFinds() {
		HostMachine host = new HostMachine(2, 2, 0.6, 0.85, 0.5, 0.3);

		double multiple = host.scaleFilling(1.5, 0.8, 2.0, 120.0);

		assertThat(multiple).isCloseTo(1.1880441965952262, within(1e-12));
	}

	@Test
	void scaleFilling_aPartAloneOnItsHyperthreads_fillsWhereEveryThreadIsBusy() {
		HostMachine own = HostMachine.ownHyperthreads(4.0, 0.6);

		double multiple = own.scaleFilling(3.0, 3.0, 4.0, 0.0);

		assertThat(multiple).isCloseTo(0.8, within(1e-12));
	}

	@Test
	void scaleFilling_noCpuWork_isInfinite() {
		assertThat(LAPTOP.scaleFilling(1.0, 0.0, 2.0, 0.0)).isInfinite();
	}

	@Test
	void scaleFilling_threadsFullAlready_isZero() {
		assertThat(LAPTOP.scaleFilling(1.0, 1.0, 1e-15, 0.0)).isZero();
	}

}
