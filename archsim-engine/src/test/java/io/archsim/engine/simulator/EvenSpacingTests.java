package io.archsim.engine.simulator;

import java.util.List;
import java.util.stream.DoubleStream;

import org.junit.jupiter.api.Test;

import io.archsim.model.document.ArrivalProcess;
import io.archsim.model.document.BurstPattern;
import io.archsim.model.document.ConstantPattern;
import io.archsim.model.document.RampPattern;
import io.archsim.model.document.TracePattern;
import io.archsim.model.document.TracePoint;
import io.archsim.model.document.TrafficPattern;
import io.archsim.model.document.Workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Evenly spaced arrivals fall where the rate has added up to one more request: a constant
 * rate keeps the gaps it always had, to the bit; a ramp from 0 starts once its rate adds
 * up to one; a request due where a trace touches 0 is sent there; and a request is
 * carried across the edge of a burst.
 */
class EvenSpacingTests {

	private static final double DURATION_S = 120;

	private static final double CLOSE_MS = 1e-9;

	@Test
	void next_aConstantRate_keepsTheGapsOf1000OverTheRateToTheBit() {
		EvenSpacing spacing = EvenSpacing.of(workload(37, new ConstantPattern()));

		List<Double> arrivals = DoubleStream.generate(spacing::next).limit(10_000).boxed().toList();

		assertThat(arrivals).containsExactlyElementsOf(
				DoubleStream.iterate(1000.0 / 37, (at) -> at + 1000.0 / 37).limit(10_000).boxed().toList());
	}

	@Test
	void next_aRampFromZero_sendsTheFirstRequestOnceTheRateAddsUpToOne() {
		EvenSpacing spacing = EvenSpacing.of(workload(0, new RampPattern(0, 100)));

		// the rate rises by 100 / 120 req/s each second: it adds up to one request at
		// t = sqrt(2 × 120 / 100) s
		assertThat(spacing.next()).isCloseTo(Math.sqrt(2 * DURATION_S / 100) * 1000, within(CLOSE_MS));
	}

	@Test
	void next_aRateOfZero_sendsNothing() {
		EvenSpacing spacing = EvenSpacing.of(workload(0, new ConstantPattern()));

		assertThat(spacing.next()).isInfinite();
	}

	@Test
	void next_aTraceFallingToZeroAndRising_sendsTheRequestDueAtTheZeroThere() {
		// from 2 req/s to 0 in 1 s and back to 2 in 1 s: one request each second, then
		// every 500 ms
		EvenSpacing spacing = EvenSpacing.of(workload(0,
				new TracePattern(List.of(new TracePoint(0, 2), new TracePoint(1, 0), new TracePoint(2, 2)), null)));

		List<Double> arrivals = DoubleStream.generate(spacing::next).limit(3).boxed().toList();

		assertThat(arrivals).satisfiesExactly((at) -> assertThat(at).isCloseTo(1000, within(CLOSE_MS)),
				(at) -> assertThat(at).isCloseTo(2000, within(CLOSE_MS)),
				(at) -> assertThat(at).isCloseTo(2500, within(CLOSE_MS)));
	}

	@Test
	void next_aRequestDueAcrossABurstsStart_isCarriedIntoTheBurst() {
		// 1 req/s, then 3 req/s from 1.5 s: half a request before the burst, the other
		// half in its first 1/6 s
		EvenSpacing spacing = EvenSpacing.of(workload(1, new BurstPattern(3, 1.5, 10)));

		List<Double> arrivals = DoubleStream.generate(spacing::next).limit(2).boxed().toList();

		assertThat(arrivals).satisfiesExactly((at) -> assertThat(at).isCloseTo(1000, within(CLOSE_MS)),
				(at) -> assertThat(at).isCloseTo(1500 + 500.0 / 3, within(CLOSE_MS)));
	}

	@Test
	void next_aTraceWhosePointsFallInTheSameMillisecond_goesOnSending() {
		// two times a trace accepts, rising strictly, that are the same time once in ms
		double time = 1.024003900000011;
		double next = Math.nextUp(time);
		EvenSpacing spacing = EvenSpacing.of(workload(0, new TracePattern(List.of(new TracePoint(0, 10),
				new TracePoint(time, 10), new TracePoint(next, 10), new TracePoint(5, 10)), null)));

		assertThat(time * 1000).as("both times are the same in ms").isEqualTo(next * 1000);

		List<Double> arrivals = DoubleStream.generate(spacing::next).limit(11).boxed().toList();

		assertThat(arrivals.getLast()).isCloseTo(1100, within(CLOSE_MS));
	}

	private static Workload workload(double rps, TrafficPattern pattern) {
		return new Workload(rps, DURATION_S, 0, 7, 0.9, ArrivalProcess.DETERMINISTIC, pattern, 1_000, 0.99, null,
				List.of(), 0);
	}

}
