package io.archsim.engine.run;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.ConstantPattern;
import io.archsim.model.document.Workload;
import io.archsim.model.text.PythonFormat;

/**
 * Whether sampled mode, the web app's "Quick check (estimated)", can answer a run
 * ({@code quick_check}): it needs a constant rate, no planned failures, and a run long
 * enough that the warm-up and a steady sample are at most half of it. Even a run it can
 * answer is simulated whole when its sample turns out not steady.
 *
 * @param eligible whether it can
 * @param reason why not, or what it will simulate
 * @param requestedS the run's length, in seconds
 * @param simulatedS the warm-up and the sample it simulates; {@code null} when it cannot
 * @param sampleS the steady sample it measures; {@code null} when it cannot
 */
public record QuickCheck(boolean eligible, String reason, double requestedS, @Nullable Double simulatedS,
		@Nullable Double sampleS) {

	/** A sample is at least this long, in seconds... */
	private static final double SAMPLE_MIN_S = 10;

	/**
	 * ...and has at least this many arrivals, so its percentiles rest on enough requests.
	 */
	private static final double SAMPLE_MIN_REQUESTS = 50_000;

	/**
	 * A run is sampled only when the warm-up and the sample are at most this share of it.
	 */
	private static final double SAMPLE_MAX_SHARE = 0.5;

	/** From this many seconds on, a time is written without decimals. */
	private static final double WHOLE_SECONDS_FROM = 10;

	/**
	 * Whether sampled mode can answer a run.
	 * @param workload the run's workload, as resolved
	 * @return the answer, and why
	 */
	public static QuickCheck of(Workload workload) {
		double duration = workload.durationS();
		if (!(workload.pattern() instanceof ConstantPattern)) {
			return no("Quick check needs constant traffic; this run's traffic is a "
					+ RecordCodec.tag(workload.pattern()) + " pattern, so it is simulated whole.", duration);
		}
		int faults = workload.faults().size();
		if (faults > 0) {
			return no("Quick check needs a run without planned failures; this one has " + faults + " planned failure"
					+ ((faults != 1) ? "s" : "") + ", so it is simulated whole.", duration);
		}
		if (workload.rps() <= 0) {
			return no("There is no traffic to sample.", duration);
		}
		double sample = Math.max(SAMPLE_MIN_S, SAMPLE_MIN_REQUESTS / workload.rps());
		double total = workload.warmupS() + sample;
		if (total > SAMPLE_MAX_SHARE * duration) {
			return no("The run is too short to gain from a quick check: the warm-up and a steady sample ("
					+ seconds(total) + ") would be more than half of its " + seconds(duration) + ". Runs of "
					+ seconds(Math.ceil(total / SAMPLE_MAX_SHARE)) + " or longer qualify.", duration);
		}
		return new QuickCheck(true,
				"Simulates the " + seconds(workload.warmupS()) + " warm-up and a " + seconds(sample)
						+ " steady sample instead of all " + seconds(duration)
						+ ", then answers from the sample if it is steady.",
				duration, total, sample);
	}

	private static QuickCheck no(String reason, double duration) {
		return new QuickCheck(false, reason, duration, null, null);
	}

	private static String seconds(double value) {
		return PythonFormat.format(value, (value >= WHOLE_SECONDS_FROM) ? ",.0f" : ".1f") + " s";
	}

}
