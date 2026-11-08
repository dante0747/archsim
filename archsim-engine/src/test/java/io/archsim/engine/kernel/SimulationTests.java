package io.archsim.engine.kernel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The simulation's clock and queue beyond SimPy's recorded programs
 * ({@link TimingCasesTests}, {@link QueueCasesTests}).
 */
class SimulationTests {

	private final Simulation simulation = new Simulation();

	@Test
	void step_nothingScheduled_isFalse() {
		boolean stepped = this.simulation.step();

		assertThat(stepped).isFalse();
	}

	@Test
	void timeout_negativeDelay_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> this.simulation.timeout(-0.5))
			.withMessage("Negative delay -0.5");
	}

	@Test
	void succeed_signalTriggeredAlready_isRefused() {
		Signal signal = this.simulation.event();
		signal.succeed();

		assertThatIllegalStateException().isThrownBy(signal::succeed)
			.withMessage("the event has already been triggered");
	}

	@Test
	void value_promiseNotTriggered_isRefused() {
		Promise<String> promise = this.simulation.promise();

		assertThatIllegalStateException().isThrownBy(promise::value)
			.withMessage("the event has no value until it is triggered");
	}

	@Test
	void runUntil_everyTwoEvents_checksAfterEachPair() {
		for (int index = 1; index <= 5; index++) {
			this.simulation.timeout(index);
		}
		List<Double> checks = new ArrayList<>();

		this.simulation.runUntil(10, 2, () -> checks.add(this.simulation.now()));

		assertThat(checks).containsExactly(2.0, 4.0);
	}

	@Test
	void runUntil_lastEventBeforeTheTime_processesIt() {
		this.simulation.timeout(3);

		long processed = this.simulation.runUntil(10, 100, () -> {
		});

		assertThat(processed).isOne();
	}

	@Test
	void runUntil_lastEventBeforeTheTime_advancesTheClockToTheTime() {
		this.simulation.timeout(3);

		this.simulation.runUntil(10, 100, () -> {
		});

		assertThat(this.simulation.now()).isEqualTo(10.0);
	}

	@Test
	void runUntil_aTimeNotLaterThanNow_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> this.simulation.runUntil(0, 1, () -> {
		})).withMessage("a chunk runs to a later time; got 0.0 at 0.0");
	}

	@Test
	void runUntil_noEventsBetweenChecks_isRefused() {
		assertThatIllegalArgumentException().isThrownBy(() -> this.simulation.runUntil(5, 0, () -> {
		})).withMessage("a chunk checks every 1 or more events; got 0");
	}

	@Property
	void step_timeoutsInAnyOrder_happenByTimeThenScheduling(
			@ForAll @Size(max = 300) List<@DoubleRange(min = 0, max = 50) Double> delays) {
		Simulation fresh = new Simulation();
		List<Happened> happened = new ArrayList<>();
		for (int index = 0; index < delays.size(); index++) {
			int scheduled = index;
			fresh.timeout(Math.floor(delays.get(index))).then(() -> happened.add(new Happened(fresh.now(), scheduled)));
		}

		KernelCases.runAll(fresh);

		assertThat(happened).hasSameSizeAs(delays)
			.isSortedAccordingTo(Comparator.comparingDouble(Happened::time).thenComparingInt(Happened::scheduled));
	}

	@Test
	void step_twoThousandTimeouts_happenByTimeThenScheduling() {
		List<Happened> happened = new ArrayList<>();
		for (int index = 0; index < 2000; index++) {
			int scheduled = index;
			this.simulation.timeout((index * 7919) % 101)
				.then(() -> happened.add(new Happened(this.simulation.now(), scheduled)));
		}

		KernelCases.runAll(this.simulation);

		assertThat(happened).hasSize(2000)
			.isSortedAccordingTo(Comparator.comparingDouble(Happened::time).thenComparingInt(Happened::scheduled));
	}

	/**
	 * A timeout that happened.
	 *
	 * @param time when
	 * @param scheduled the order it was scheduled in
	 */
	private record Happened(double time, int scheduled) {

	}

}
