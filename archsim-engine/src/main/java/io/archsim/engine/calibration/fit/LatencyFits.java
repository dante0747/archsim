package io.archsim.engine.calibration.fit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.DoubleUnaryOperator;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.distribution.Distribution;
import io.archsim.model.text.PythonFormat;

/**
 * Measured latencies fitted to ArchSim's distributions: a histogram's quantiles by linear
 * interpolation inside each bucket, as {@code histogram_quantile} gives them, or samples'
 * quantiles directly, fitted to a lognormal (whose median and p95 match exactly) or kept
 * as an empirical quantile table.
 *
 * <p>
 * The fit error is the largest relative error between fitted and observed quantiles: for
 * samples at {@link #CHECK_QUANTILES}; for a histogram where its data is exact, at the
 * bucket bounds between the observed p50 and p99. An empirical table built from a
 * histogram passes through every bound, so it has no error, and a note gives the buckets'
 * widths.
 */
public final class LatencyFits {

	/** The quantiles a fit is checked at. */
	static final List<Double> CHECK_QUANTILES = List.of(0.5, 0.75, 0.9, 0.95, 0.99);

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final double MEDIAN = 0.5;

	private static final double P95 = 0.95;

	private static final double P99 = 0.99;

	/**
	 * The standard normal quantile of each checked quantile, as the Python writes them.
	 */
	private static final Map<Double, Double> Z = Map.of(MEDIAN, 0.0, 0.75, 0.6744897501960817, 0.9, 1.2815515655446004,
			P95, 1.6448536269514722, P99, 2.3263478740408408);

	private static final double SMALLEST_MEDIAN = 1e-6;

	private static final double P95_ABOVE_MEDIAN = 1.0001;

	private static final int DISTRIBUTION_DIGITS = 6;

	private static final int ERROR_DIGITS = 4;

	private static final int TABLE_STEPS = 50;

	private static final List<Double> TABLE_TAIL = List.of(0.99, 0.995, 0.999, 1.0);

	private LatencyFits() {
	}

	/**
	 * A histogram fitted to a distribution.
	 * @param histogram the histogram
	 * @param kind a lognormal or an empirical table
	 * @return the fit, with a note when observations lay above the highest bound
	 * @throws IllegalArgumentException when the histogram has no observations
	 */
	public static Fit fitHistogram(Histogram histogram, FitKind kind) {
		Map<Double, Double> observed = new LinkedHashMap<>();
		for (double quantile : CHECK_QUANTILES) {
			histogram.quantile(quantile).ifPresent((value) -> observed.put(quantile, value));
		}
		if (!observed.containsKey(MEDIAN) || !observed.containsKey(P95)) {
			throw new IllegalArgumentException("the histogram has no observations");
		}
		List<String> notes = new ArrayList<>();
		double beyond = histogram.beyondShare();
		if (beyond > 0) {
			notes.add(beyondNote(histogram, beyond, kind));
		}
		if (kind == FitKind.EMPIRICAL) {
			Fit fit = fitQuantiles(observed, kind, HistogramTable.of(histogram), histogram.total());
			notes.add(HistogramTable.widthsNote(histogram));
			return new Fit(fit.distribution(), null, observed, histogram.total(), notes);
		}
		Fit fit = fitQuantiles(observed, kind, null, histogram.total());
		ObjectNode distribution = fit.distribution();
		Double atBounds = errorAtBounds(histogram, distribution.path("median").doubleValue(),
				distribution.path("p95").doubleValue());
		return new Fit(distribution, (atBounds != null) ? atBounds : fit.error(), observed, histogram.total(), notes);
	}

	/**
	 * Samples fitted to a distribution.
	 * @param samples the samples, in milliseconds
	 * @param kind a lognormal, or an empirical table with a denser tail
	 * @return the fit
	 * @throws IllegalArgumentException without samples
	 */
	public static Fit fitSamples(List<Double> samples, FitKind kind) {
		if (samples.isEmpty()) {
			throw new IllegalArgumentException("no samples");
		}
		List<Double> sorted = samples.stream().sorted().toList();
		Map<Double, Double> observed = new LinkedHashMap<>();
		CHECK_QUANTILES.forEach((quantile) -> observed.put(quantile, sampleQuantile(sorted, quantile)));
		List<QuantilePoint> table = null;
		if (kind == FitKind.EMPIRICAL) {
			List<QuantilePoint> points = new ArrayList<>();
			for (int i = 0; i < TABLE_STEPS; i++) {
				double quantile = (double) i / TABLE_STEPS;
				points.add(new QuantilePoint(quantile, sampleQuantile(sorted, quantile)));
			}
			TABLE_TAIL.forEach((quantile) -> points.add(new QuantilePoint(quantile, sampleQuantile(sorted, quantile))));
			table = QuantilePoint.monotone(points);
		}
		return fitQuantiles(observed, kind, table, sorted.size());
	}

	/**
	 * A quantile of sorted samples, interpolated linearly between the two nearest.
	 * @param sorted the samples, ascending
	 * @param quantile the quantile
	 * @return its value
	 * @throws IllegalArgumentException without samples
	 */
	public static double sampleQuantile(List<Double> sorted, double quantile) {
		if (sorted.isEmpty()) {
			throw new IllegalArgumentException("no samples");
		}
		double position = quantile * (sorted.size() - 1);
		int lower = (int) Math.floor(position);
		int upper = Math.min(lower + 1, sorted.size() - 1);
		return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * (position - lower);
	}

	/**
	 * Observed quantiles fitted to a lognormal, or kept as a table.
	 * @param observed quantile to milliseconds, with the median and the p95
	 * @param kind a lognormal, or the table when there is one
	 * @param table the empirical table; {@code null} for none
	 * @param count how many observations the quantiles rest on
	 * @return the fit and its error at the observed quantiles
	 */
	static Fit fitQuantiles(Map<Double, Double> observed, FitKind kind, @Nullable List<QuantilePoint> table,
			double count) {
		double median = Math.max(observedAt(observed, MEDIAN), SMALLEST_MEDIAN);
		double p95 = Math.max(observedAt(observed, P95), median * P95_ABOVE_MEDIAN);
		ObjectNode distribution = NODES.objectNode();
		DoubleUnaryOperator fitted;
		if (kind == FitKind.EMPIRICAL && table != null && !table.isEmpty()) {
			distribution.put("kind", "empirical");
			ArrayNode quantiles = distribution.putArray("quantiles");
			table.forEach((point) -> quantiles.addArray()
				.add(PythonRound.round(point.share(), DISTRIBUTION_DIGITS))
				.add(PythonRound.round(point.value(), DISTRIBUTION_DIGITS)));
			fitted = tableQuantiles(table);
		}
		else {
			distribution.put("kind", "lognormal")
				.put("median", PythonRound.round(median, DISTRIBUTION_DIGITS))
				.put("p95", PythonRound.round(p95, DISTRIBUTION_DIGITS));
			fitted = (quantile) -> lognormalQuantile(median, p95, quantile);
		}
		return new Fit(distribution, fitError(observed, fitted), observed, count, List.of());
	}

	/**
	 * A lognormal's quantile, from its median and p95.
	 * @param median the median
	 * @param p95 the p95
	 * @param quantile one of the checked quantiles
	 * @return the quantile's value
	 */
	static double lognormalQuantile(double median, double p95, double quantile) {
		return median * Math.exp(sigma(median, p95) * z(quantile));
	}

	private static double sigma(double median, double p95) {
		return (p95 > median) ? Math.log(p95 / median) / z(P95) : 0.0;
	}

	/**
	 * The standard normal quantile of a checked quantile, as the Python writes it.
	 * @param quantile one of {@link #CHECK_QUANTILES}
	 * @return its standard normal quantile
	 */
	private static double z(double quantile) {
		return Objects.requireNonNull(Z.get(quantile), () -> "not a checked quantile: " + quantile);
	}

	/**
	 * An observed quantile the fit needs.
	 * @param observed the observed quantiles
	 * @param quantile the median or the p95
	 * @return its value
	 */
	private static double observedAt(Map<Double, Double> observed, double quantile) {
		return Objects.requireNonNull(observed.get(quantile), () -> "the observed quantiles need " + quantile);
	}

	private static DoubleUnaryOperator tableQuantiles(List<QuantilePoint> table) {
		return (quantile) -> QuantilePoint.interpolate(table, quantile);
	}

	/**
	 * The largest relative error of the fitted quantiles against the observed ones.
	 * @param observed quantile to milliseconds
	 * @param fitted the fit's quantile function
	 * @return the error, rounded to four places; 0 when no observed quantile is above 0
	 */
	private static double fitError(Map<Double, Double> observed, DoubleUnaryOperator fitted) {
		OptionalDouble largest = observed.entrySet()
			.stream()
			.filter((quantile) -> quantile.getValue() > 0)
			.mapToDouble((quantile) -> Math.abs(fitted.applyAsDouble(quantile.getKey()) - quantile.getValue())
					/ quantile.getValue())
			.max();
		return largest.isPresent() ? PythonRound.round(largest.getAsDouble(), ERROR_DIGITS) : 0.0;
	}

	/**
	 * The largest relative error of a lognormal at the bucket bounds whose observed share
	 * lies between p50 and p99: where the fit puts that share of observations, against
	 * the bound the histogram counted them under.
	 * @param histogram the histogram
	 * @param median the lognormal's median
	 * @param p95 the lognormal's p95
	 * @return the error; {@code null} when no bound lies in that range
	 */
	private static @Nullable Double errorAtBounds(Histogram histogram, double median, double p95) {
		double sigma = sigma(median, p95);
		OptionalDouble largest = OptionalDouble.empty();
		for (int i = 0; i < histogram.bounds().size(); i++) {
			double bound = histogram.bounds().get(i);
			double share = histogram.cumulative().get(i) / histogram.total();
			if (share >= MEDIAN && share <= P99 && bound > 0) {
				double error = Math.abs(median * Math.exp(sigma * NormalQuantile.of(share)) - bound) / bound;
				largest = OptionalDouble.of(Math.max(largest.orElse(error), error));
			}
		}
		return largest.isPresent() ? PythonRound.round(largest.getAsDouble(), ERROR_DIGITS) : null;
	}

	private static String beyondNote(Histogram histogram, double beyond, FitKind kind) {
		double top = histogram.bounds().getLast();
		String topText = PythonFormat.format(top, "g");
		return PythonFormat.format(beyond, ".1%") + " of observations were above the highest bucket bound (" + topText
				+ " ms); a histogram does not say how far above"
				+ ((kind == FitKind.EMPIRICAL)
						? ", so the table spreads them between " + topText + " and "
								+ PythonFormat.format(Math.min(Distribution.MAX_SAMPLE_MS, top * 2), "g") + " ms"
						: ", so quantiles that fall there are taken as " + topText + " ms");
	}

}
