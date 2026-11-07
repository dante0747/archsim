package io.archsim.engine.calibration.fit;

import java.util.ArrayList;
import java.util.List;

import io.archsim.model.distribution.Distribution;
import io.archsim.model.text.PythonFormat;

/**
 * A histogram as an empirical quantile table: the share of observations at or below each
 * bucket bound.
 */
final class HistogramTable {

	private static final List<Double> WIDTH_QUANTILES = List.of(0.5, 0.95, 0.99);

	private static final double PERCENT = 100;

	private HistogramTable() {
	}

	/**
	 * The table. It ends at the first bound whose cumulative count reaches the total
	 * (nothing was observed above it); only observations in the {@code +Inf} bucket
	 * extend it, up to twice the highest finite bound.
	 * @param histogram the histogram, with observations
	 * @return the table, from share 0 to 1
	 */
	static List<QuantilePoint> of(Histogram histogram) {
		List<QuantilePoint> points = new ArrayList<>(List.of(new QuantilePoint(0.0, 0.0)));
		for (int i = 0; i < histogram.bounds().size(); i++) {
			double bound = histogram.bounds().get(i);
			double share = histogram.cumulative().get(i) / histogram.total();
			if (share >= 1.0 - Histogram.FULL_EPS) {
				points.add(new QuantilePoint(1.0, bound));
				return QuantilePoint.monotone(points);
			}
			if (share > points.getLast().share()) {
				points.add(new QuantilePoint(share, bound));
			}
		}
		points.add(new QuantilePoint(1.0, Math.min(Distribution.MAX_SAMPLE_MS, histogram.bounds().getLast() * 2)));
		return QuantilePoint.monotone(points);
	}

	/**
	 * Why a table from buckets has no fit error, with the widths of the buckets that hold
	 * its p50, p95 and p99.
	 * @param histogram the histogram
	 * @return the note
	 */
	static String widthsNote(Histogram histogram) {
		List<String> widths = new ArrayList<>();
		for (double quantile : WIDTH_QUANTILES) {
			histogram.bucketOf(quantile)
				.ifPresent((bucket) -> widths
					.add("p" + Math.round(quantile * PERCENT) + " between " + PythonFormat.format(bucket.lower(), "g")
							+ " and " + PythonFormat.format(bucket.upper(), "g") + " ms"));
		}
		return "an empirical table from histogram buckets passes through every bucket bound, so no fit error can "
				+ "be measured; inside a bucket it is only as precise as the bucket"
				+ (widths.isEmpty() ? "" : " (" + String.join(", ", widths) + ")");
	}

}
