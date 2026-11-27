package io.archsim.engine.simulator;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.random.RandomStreams;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.document.ArrivalProcess;
import io.archsim.model.document.ConstantPattern;
import io.archsim.model.document.Workload;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Arrivals are generated only for a peak rate that is a finite number: the arrival loop
 * ends by comparing times with the run's end, and no comparison with NaN ends it.
 */
class ArrivalsTests {

	private static final double DURATION_MS = 10_000;

	@ParameterizedTest
	@ValueSource(doubles = { Double.NaN, Double.POSITIVE_INFINITY })
	void new_aPeakRateThatIsNotFinite_isRefused(double rps) {
		Workload workload = new Workload(rps, 10, 1, 7, 0.9, ArrivalProcess.POISSON, new ConstantPattern(), 1_000, 0.99,
				null, List.of(), 0);
		SimContext context = new SimContext(new Simulation(), new RandomStreams(7),
				new Timeline(DURATION_MS, 1_000, DURATION_MS), workload);

		assertThatIllegalArgumentException()
			.isThrownBy(() -> new Arrivals(context, workload, new ClientPicker(context, List.of())))
			.withMessage("the workload's peak rate is not a finite number of requests per second");
	}

}
