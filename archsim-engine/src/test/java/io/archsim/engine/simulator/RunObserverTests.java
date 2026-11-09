package io.archsim.engine.simulator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A run's progress and its cancel, as the frozen engine's {@code progress} and
 * {@code should_cancel} hooks: progress after each chunk the run simulates and 1.0 once
 * its report is written, and a cancel looked for wherever the wall clock is, before it.
 * The progress the frozen engine reported for the same designs is recorded in each test.
 */
class RunObserverTests {

	/** Long enough for a quick check to sample 50,000 requests in 25 s, and steady. */
	private static final String LONG_AND_STEADY = """
			{"seed": 7, "warmupS": 2, "durationS": 600, "rps": 2000, "arrivalProcess": "deterministic"}""";

	/** Long enough for a quick check to sample 50,000 requests in 50 s. */
	private static final String SAMPLED_IN_50_S = """
			{"seed": 7, "warmupS": 2, "durationS": 120, "rps": 1000, "arrivalProcess": "deterministic"}""";

	private static final RunRequest QUICK_CHECK = RunRequest.of(null).withMode(SimulationMode.SAMPLED);

	private final Simulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped());

	@Test
	void run_aRunThatEndsOnceIdle_reportsTheChunksItSimulatedThenOne() {
		Recorder recorder = new Recorder((_) -> false);

		this.simulator.run(SmallDesigns.clientsCallingAnApi(SmallDesigns.QUIET), RunRequest.of(null), recorder);

		assertThat(recorder.progress).containsExactly(0.005, 0.01, 1.0);
	}

	@Test
	void run_aWholeRun_reportsEachChunkThenOne() {
		Recorder recorder = new Recorder((_) -> false);

		this.simulator.run(SmallDesigns.clientsCallingAnApi(SmallDesigns.STEADY), RunRequest.of(null), recorder);

		assertThat(recorder.progress).hasSize(122).startsWith(0.005, 0.01, 0.015, 0.02).endsWith(0.6, 0.605, 1.0);
	}

	@Test
	void run_aQuickCheckAnsweredFromItsSample_reportsTheSamplesChunksThenOne() {
		Recorder recorder = new Recorder((_) -> false);

		JsonNode report = this.simulator.run(SmallDesigns.clientsCallingAnApi(LONG_AND_STEADY), QUICK_CHECK, recorder);

		assertThat(report.path("simulationMode").path("kind").stringValue()).isEqualTo("sampled");
		assertThat(recorder.progress).hasSize(176).endsWith(0.87, 0.875, 1.0);
	}

	@Test
	void run_aQuickCheckTooShortToSample_reportsTheWholeRunsChunksThenOne() {
		Recorder recorder = new Recorder((_) -> false);

		JsonNode report = this.simulator.run(SmallDesigns.clientsCallingAnApi(SmallDesigns.STEADY), QUICK_CHECK,
				recorder);

		assertThat(report.has("simulationMode")).isFalse();
		assertThat(recorder.progress).hasSize(122).startsWith(0.005, 0.01, 0.015, 0.02).endsWith(0.6, 0.605, 1.0);
	}

	@Test
	void run_aQuickCheckWhoseSampleIsNotSteady_reportsTheSampleThenTheWholeRunFromTheStart() {
		// the API refuses half the load, so the sample does not keep up with it
		Recorder recorder = new Recorder((_) -> false);

		JsonNode report = this.simulator.run(SmallDesigns.clientsCallingARateLimitedApi(500, SAMPLED_IN_50_S),
				QUICK_CHECK, recorder);

		assertThat(report.path("simulationMode").path("kind").stringValue()).isEqualTo("full");
		assertThat(recorder.progress).hasSize(382).endsWith(0.965, 0.97, 1.0);
		assertThat(recorder.progress.indexOf(1.0)).isEqualTo(186);
		assertThat(recorder.progress.subList(185, 189)).containsExactly(0.93, 1.0, 0.005, 0.01);
	}

	@Test
	void run_cancelledAfterItsFirstChunk_stopsThereInTheFrozenEnginesWords() {
		Recorder recorder = new Recorder((progress) -> !progress.isEmpty());

		assertThatExceptionOfType(SimulationCancelledException.class)
			.isThrownBy(() -> this.simulator.run(SmallDesigns.clientsCallingAnApi(SmallDesigns.STEADY),
					RunRequest.of(null), recorder))
			.withMessage("simulation cancelled");
		assertThat(recorder.progress).containsExactly(0.005);
	}

	@Test
	void run_cancelledInABusyChunk_stopsBeforeTheChunksEnd() {
		// the first chunk holds 3,000 requests: the look after 4,096 events stops it
		Recorder recorder = new Recorder((_) -> true);

		assertThatExceptionOfType(SimulationCancelledException.class).isThrownBy(() -> this.simulator
			.run(SmallDesigns.clientsCallingAnApi(SmallDesigns.BUSY), RunRequest.of(null), recorder));
		assertThat(recorder.progress).isEmpty();
	}

	@Test
	void run_cancelledAndPastItsWallClockLimit_isCancelled() {
		AtomicLong nanos = new AtomicLong();
		Simulator late = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped(),
				() -> nanos.getAndAdd(1_000_000_000L));
		RunRequest noTime = RunRequest.of(null).withLimits(new SimulationLimits(2_000_000, 0.0));

		assertThatExceptionOfType(SimulationCancelledException.class).isThrownBy(() -> late
			.run(SmallDesigns.clientsCallingAnApi(SmallDesigns.STEADY), noTime, new Recorder((_) -> true)));
	}

	@Test
	void run_observed_givesTheReportAnUnobservedRunGives() {
		JsonNode design = SmallDesigns.clientsCallingAnApi(SmallDesigns.STEADY);

		JsonNode observed = this.simulator.run(design, RunRequest.of(null), new Recorder((_) -> false));
		JsonNode unobserved = this.simulator.run(design, RunRequest.of(null));

		assertThat(observed.path("provenance").path("resultDigest"))
			.isEqualTo(unobserved.path("provenance").path("resultDigest"));
	}

	/**
	 * An observer that keeps each progress it is told, and asks the run to stop once what
	 * it has been told so far meets a test.
	 */
	private static final class Recorder implements RunObserver {

		private final Predicate<List<Double>> cancelWhen;

		private final List<Double> progress = new ArrayList<>();

		Recorder(Predicate<List<Double>> cancelWhen) {
			this.cancelWhen = cancelWhen;
		}

		@Override
		public void progress(double fraction) {
			this.progress.add(fraction);
		}

		@Override
		public boolean cancelRequested() {
			return this.cancelWhen.test(this.progress);
		}

	}

}
