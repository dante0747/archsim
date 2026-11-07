package io.archsim.engine.calibration.resources;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.calibration.resources.FitErrors.Comparison;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;

/**
 * A part's {@code cpuShare}, as the Python engine's {@code fit_cpu_share} fits it: CPU
 * per request is the rate of CPU seconds over the rate of requests, summed over the
 * range, so the busiest times weigh most, which is where a part is sized; the share is
 * that over the mean service time, at most 1.
 */
public final class CpuFits {

	private static final double MS_PER_S = 1000.0;

	private static final int SHARE_DIGITS = 4;

	private CpuFits() {
	}

	/**
	 * The share, and CPU per request against the rate per replica.
	 * @param points the whole part's rate and CPU cores in use; points that are not
	 * finite or below 0 are left out
	 * @param serviceMs the mean service time, in milliseconds
	 * @param replicas the part's replicas, which share its rate; at least 1 is counted
	 * @return the fit; empty without traffic or without a service time
	 */
	public static Optional<CpuFit> fitShare(List<RatePoint> points, double serviceMs, double replicas) {
		List<RatePoint> kept = points.stream()
			.filter((point) -> Double.isFinite(point.rate()) && Double.isFinite(point.value()) && point.rate() >= 0
					&& point.value() >= 0)
			.toList();
		double totalRate = FloatSum.of(kept.stream().mapToDouble(RatePoint::rate));
		if (kept.isEmpty() || totalRate <= 0 || serviceMs <= 0) {
			return Optional.empty();
		}
		double cpuMs = MS_PER_S * FloatSum.of(kept.stream().mapToDouble(RatePoint::value)) / totalRate;
		double raw = cpuMs / serviceMs;
		boolean capped = raw > 1.0;
		double share = PythonRound.round(Math.min(1.0, raw), SHARE_DIGITS);
		List<String> notes = capped ? List.of(cappedNote(cpuMs, serviceMs)) : List.of();
		double perReplica = Math.max(1.0, replicas);
		LoadCurve curve = LoadCurves
			.fit(kept.stream()
				.map((point) -> new RatePoint(point.rate() / perReplica, point.value() / perReplica))
				.toList(), cpuMs, LoadCurves.MAX_BINS)
			.orElse(null);
		return Optional.of(new CpuFit(share, cpuMs, serviceMs, capped, error(kept, serviceMs * share / MS_PER_S), curve,
				kept.size(), notes));
	}

	/**
	 * The largest relative error of the fitted cores at and above the median rate.
	 * @param points the points fitted
	 * @param coresPerRequest the fitted cores per request per second
	 * @return the error; {@code null} when no core was measured there
	 */
	private static @Nullable Double error(List<RatePoint> points, double coresPerRequest) {
		List<Comparison> comparisons = new ArrayList<>();
		for (int i : FitErrors.busyHalf(points.stream().map(RatePoint::rate).toList())) {
			comparisons.add(new Comparison(points.get(i).rate() * coresPerRequest, points.get(i).value()));
		}
		return FitErrors.maxRelative(comparisons);
	}

	private static String cappedNote(double cpuMs, double serviceMs) {
		return PythonFormat.format(cpuMs, ".3g") + " ms of CPU per request is more than the "
				+ PythonFormat.format(serviceMs, ".3g") + " ms service time: the process spends CPU outside the "
				+ "handler time (HTTP parsing, drivers, garbage collection). The CPU share is at most 1, so the model "
				+ "under-counts CPU; use a service time that covers the CPU per request";
	}

}
