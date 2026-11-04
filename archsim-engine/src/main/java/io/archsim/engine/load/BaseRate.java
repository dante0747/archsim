package io.archsim.engine.load;

import java.util.Arrays;
import java.util.stream.IntStream;

import io.archsim.model.document.RampPattern;
import io.archsim.model.document.TracePattern;
import io.archsim.model.document.Workload;

/**
 * The steady base rate of a workload, beside its peak ({@link Workload#peakRate()}): what
 * a part carries most of the time, for monthly per-request costs and CPU credits.
 */
public final class BaseRate {

	/** A trace's base rate is the median of this many rates spread over the run. */
	private static final int TRACE_SAMPLES = 400;

	/** Each sample is taken in the middle of its share of the run. */
	private static final double MIDDLE = 0.5;

	private BaseRate() {
	}

	/**
	 * The base rate: a constant pattern's rate; a burst's rate outside the burst; the
	 * lower end of a ramp (the traffic before or after it); a trace's median over the
	 * run.
	 * @param workload the workload
	 * @return the rate, in requests per second
	 */
	public static double of(Workload workload) {
		return switch (workload.pattern()) {
			case RampPattern ramp -> Math.min(ramp.startRps(), ramp.endRps());
			case TracePattern trace -> traceMedian(trace, workload.durationS());
			// a constant pattern's rate, and a burst's outside the burst
			default -> workload.rps();
		};
	}

	/**
	 * The median of a trace's rate at 400 evenly spaced moments of the run.
	 * @param trace the trace
	 * @param durationS the run's duration, in seconds
	 * @return the median rate; the rate at the start when the run has no duration
	 */
	private static double traceMedian(TracePattern trace, double durationS) {
		if (durationS <= 0) {
			return trace.rate(0.0);
		}
		double[] samples = IntStream.range(0, TRACE_SAMPLES)
			.mapToDouble((sample) -> trace.rate(durationS * (sample + MIDDLE) / TRACE_SAMPLES))
			.toArray();
		Arrays.sort(samples);
		return (samples[TRACE_SAMPLES / 2 - 1] + samples[TRACE_SAMPLES / 2]) / 2;
	}

}
