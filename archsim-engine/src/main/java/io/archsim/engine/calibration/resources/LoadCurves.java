package io.archsim.engine.calibration.resources;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.calibration.resources.FitErrors.Comparison;
import io.archsim.engine.numeric.FloatSum;

/**
 * CPU per request as it changes with load, as the Python engine's {@code fit_load_curve}
 * fits it: on real stacks it falls as load rises (requests share event-loop wake-ups,
 * system calls and driver work). The points are sorted by the rate one replica gets into
 * bins of equal count, each bin's CPU per request taken (its cores over its rate, so busy
 * points weigh most), and the closest curve that never rises with rate fitted (pool
 * adjacent violators): a rise is noise or contention, which a falling cost must not be
 * extrapolated from.
 */
public final class LoadCurves {

	/** The busiest bin's rate must be at least this multiple of the quietest's. */
	public static final double MIN_SPREAD = 1.5;

	/** The most bins by rate a curve has. */
	public static final int MAX_BINS = 6;

	/**
	 * The share a curve's factors must span for a calibration to set it: less is within
	 * CPU counters' noise.
	 */
	public static final double MIN_CHANGE = 0.10;

	private static final double MS_PER_S = 1000.0;

	private LoadCurves() {
	}

	/**
	 * The curve of one replica's CPU per request against its rate.
	 * @param points the replica's rate and CPU cores in use; points that are not finite,
	 * with no rate or with CPU below 0 are left out
	 * @param referenceMs the CPU per request a factor of 1 stands for; {@code null}, or 0
	 * or less, for the CPU per request over the whole range
	 * @param maxBins the most bins
	 * @return the curve; empty with fewer than 2 points, when the busiest bin's rate is
	 * under {@link #MIN_SPREAD} times the quietest's, or without CPU
	 */
	public static Optional<LoadCurve> fit(List<RatePoint> points, @Nullable Double referenceMs, int maxBins) {
		List<RatePoint> sorted = points.stream()
			.filter((point) -> Double.isFinite(point.rate()) && Double.isFinite(point.value()) && point.rate() > 0
					&& point.value() >= 0)
			.sorted(Comparator.comparingDouble(RatePoint::rate).thenComparingDouble(RatePoint::value))
			.toList();
		if (sorted.size() < 2) {
			return Optional.empty();
		}
		List<Bin> bins = bins(sorted, Math.max(2, Math.min(maxBins, sorted.size())));
		if (bins.getLast().meanRate() < MIN_SPREAD * bins.getFirst().meanRate()) {
			return Optional.empty();
		}
		List<Double> measured = bins.stream().map(Bin::cpuMs).toList();
		List<Double> weights = bins.stream().map(Bin::rate).toList();
		double reference = reference(referenceMs, measured, weights);
		return (reference > 0) ? Optional.of(curve(bins, neverRising(measured, weights), reference)) : Optional.empty();
	}

	/**
	 * The weighted least-squares fit that never rises (pool adjacent violators): where a
	 * later value is higher, the run is replaced by its weighted mean.
	 * @param values the values, in order
	 * @param weights each value's weight
	 * @return the fitted values
	 */
	static List<Double> neverRising(List<Double> values, List<Double> weights) {
		List<Block> blocks = new ArrayList<>();
		for (int i = 0; i < values.size(); i++) {
			blocks.add(new Block(values.get(i), weights.get(i), 1));
			while (blocks.size() > 1 && blocks.getLast().mean() > blocks.get(blocks.size() - 2).mean()) {
				Block later = blocks.removeLast();
				Block earlier = blocks.removeLast();
				blocks.add(new Block(
						(earlier.mean() * earlier.weight() + later.mean() * later.weight())
								/ (earlier.weight() + later.weight()),
						earlier.weight() + later.weight(), earlier.count() + later.count()));
			}
		}
		List<Double> fitted = new ArrayList<>();
		blocks.forEach((block) -> {
			for (int i = 0; i < block.count(); i++) {
				fitted.add(block.mean());
			}
		});
		return fitted;
	}

	/**
	 * The curve of the bins' fitted CPU per request.
	 * @param bins the bins, by rate
	 * @param fitted each bin's fitted CPU per request
	 * @param reference the CPU per request a factor of 1 stands for
	 * @return the curve, with each bin's measured CPU per request and the fit's error
	 */
	private static LoadCurve curve(List<Bin> bins, List<Double> fitted, double reference) {
		List<RatePoint> factors = new ArrayList<>();
		List<RatePoint> measured = new ArrayList<>();
		List<Comparison> comparisons = new ArrayList<>();
		for (int i = 0; i < bins.size(); i++) {
			Bin bin = bins.get(i);
			factors.add(new RatePoint(bin.meanRate(), fitted.get(i) / reference));
			measured.add(new RatePoint(bin.meanRate(), bin.cpuMs()));
			comparisons.add(new Comparison(fitted.get(i), bin.cpuMs()));
		}
		return new LoadCurve(factors, reference, measured, FitErrors.maxRelative(comparisons));
	}

	/**
	 * Sorted points in bins of equal count, as Python slices
	 * {@code pts[i*n//k:(i+1)*n//k]}.
	 * @param sorted the points, by rate
	 * @param count how many bins
	 * @return the bins that are not empty
	 */
	private static List<Bin> bins(List<RatePoint> sorted, int count) {
		List<Bin> bins = new ArrayList<>();
		int size = sorted.size();
		for (int i = 0; i < count; i++) {
			List<RatePoint> points = sorted.subList(i * size / count, (i + 1) * size / count);
			if (!points.isEmpty()) {
				bins.add(Bin.of(points));
			}
		}
		return bins;
	}

	/**
	 * The CPU per request a factor of 1 stands for.
	 * @param referenceMs the one asked for; {@code null}, or 0 or less, for none
	 * @param measured each bin's CPU per request
	 * @param weights each bin's summed rate
	 * @return the one asked for, else the CPU per request over the whole range, busy bins
	 * weighing most
	 */
	private static double reference(@Nullable Double referenceMs, List<Double> measured, List<Double> weights) {
		if (referenceMs != null && referenceMs > 0) {
			return referenceMs;
		}
		double[] products = new double[measured.size()];
		for (int i = 0; i < products.length; i++) {
			products[i] = measured.get(i) * weights.get(i);
		}
		return FloatSum.of(Arrays.stream(products)) / FloatSum.of(weights.stream().mapToDouble(Double::doubleValue));
	}

	/**
	 * One bin of points.
	 *
	 * @param rate the summed rate
	 * @param meanRate the mean rate
	 * @param cpuMs the CPU per request: the summed cores over the summed rate
	 */
	private record Bin(double rate, double meanRate, double cpuMs) {

		static Bin of(List<RatePoint> points) {
			double rate = FloatSum.of(points.stream().mapToDouble(RatePoint::rate));
			double cores = FloatSum.of(points.stream().mapToDouble(RatePoint::value));
			return new Bin(rate, rate / points.size(), MS_PER_S * cores / rate);
		}

	}

	/**
	 * A run of values replaced by their weighted mean.
	 *
	 * @param mean the mean
	 * @param weight the summed weight
	 * @param count how many values
	 */
	private record Block(double mean, double weight, int count) {

	}

}
