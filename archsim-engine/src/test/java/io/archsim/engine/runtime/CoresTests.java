package io.archsim.engine.runtime;

import org.junit.jupiter.api.Test;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Gauge;
import io.archsim.engine.metrics.Timeline;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A part's CPU figures from its busy executors, as the Python engine's
 * {@code core_utilization} and {@code busy_cores_series} give them.
 */
class CoresTests {

	private final Timeline timeline = new Timeline(1000, 0, 1000);

	@Test
	void utilization_executorsBusyAllWindow_isTheirShare() {
		Gauge busy = busyFromStart(2);

		assertThat(Cores.utilization(busy, 3)).hasValue(0.6667);
	}

	@Test
	void utilization_noExecutors_isEmpty() {
		Gauge busy = busyFromStart(2);

		assertThat(Cores.utilization(busy, 0)).isEmpty();
	}

	@Test
	void busyCores_executorsBelowOneVcpu_countAtTheirSpeed() {
		Gauge busy = busyFromStart(2);

		assertThat(Cores.busyCores(busy, 0.5)).hasSize(this.timeline.buckets()).containsOnly(1.0);
	}

	@Test
	void run_anInstanceThatTellsItselfApart_countsItsWorkInItsOwnGaugeAndThePartsAlike() {
		Simulation simulation = new Simulation();
		Gauge part = new Gauge(this.timeline);
		Gauge instance = new Gauge(this.timeline);
		Cores cores = new Cores(simulation, 1.0, 1, new Cores.Accounts(part, instance, FullCores.INSTANCE));

		cores.run(500, () -> {
		});
		simulation.runUntil(1000, 1000, () -> {
		});
		part.finish(1000);
		instance.finish(1000);

		assertThat(instance.windowMean()).isEqualTo(0.5);
		assertThat(part.windowMean()).isEqualTo(0.5);
	}

	// executors busy from the start to the end of the run
	private Gauge busyFromStart(int executors) {
		Gauge busy = new Gauge(this.timeline);
		busy.add(0, executors);
		busy.finish(1000);
		return busy;
	}

}
