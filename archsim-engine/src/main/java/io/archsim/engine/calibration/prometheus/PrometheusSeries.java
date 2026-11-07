package io.archsim.engine.calibration.prometheus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;

import io.archsim.engine.calibration.fit.Histogram;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.model.text.PythonText;

/**
 * What a Prometheus range query returned (a matrix: series of
 * {@code [timestamp, "value"]} points), read into what calibration fits: a histogram
 * averaged over the range, one figure, or the series added up per timestamp.
 *
 * <p>
 * A point is a two-item list. The Python unpacks any two-item iterable, so it also reads
 * a two-character string as a point; the Java refuses one as malformed.
 */
public final class PrometheusSeries {

	private static final String MALFORMED_LE = "a histogram series has a malformed 'le' label";

	private PrometheusSeries() {
	}

	/**
	 * A histogram from {@code *_bucket} series, one per {@code le}, each averaged over
	 * the range.
	 * @param result the query's series
	 * @param unit the unit of the bounds
	 * @return the histogram, in milliseconds
	 * @throws PrometheusException when a series has no {@code le} label or a malformed
	 * one, has malformed points, or no series has data in the range
	 */
	public static Histogram histogram(JsonNode result, LatencyUnit unit) {
		Map<Double, Double> buckets = new LinkedHashMap<>();
		for (JsonNode series : result) {
			JsonNode le = series.path("metric").path("le");
			if (le.isMissingNode() || le.isNull()) {
				throw new PrometheusException(
						"a histogram query must return series with an 'le' label (use sum by (le))");
			}
			OptionalDouble mean = timeMean(series);
			if (mean.isPresent()) {
				buckets.merge(DictKeys.of(buckets, bound(le)), mean.getAsDouble(), Double::sum);
			}
		}
		if (buckets.isEmpty()) {
			throw new PrometheusException("the histogram query returned no data in the range");
		}
		return Histogram.fromBuckets(buckets, unit.milliseconds());
	}

	/**
	 * The series added up per timestamp, reduced to one figure.
	 * @param result the query's series
	 * @param how how the figure is taken
	 * @return the figure
	 * @throws PrometheusException when a series has malformed points, or the query
	 * returned no data in the range
	 */
	public static double reduce(JsonNode result, Reduction how) {
		List<Double> points = summed(result).stream().map(Point::value).toList();
		if (points.isEmpty()) {
			throw new PrometheusException("the query returned no data in the range");
		}
		return switch (how) {
			case MAX -> points.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
			case MIN -> points.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
			case LAST -> points.getLast();
			case MEAN -> FloatSum.of(points.stream().mapToDouble(Double::doubleValue)) / points.size();
		};
	}

	/**
	 * The series added up per timestamp (one series stays as it is).
	 * @param result the query's series
	 * @return a point per timestamp, in time order
	 * @throws PrometheusException when a series has malformed points
	 */
	public static List<Point> summed(JsonNode result) {
		Map<Double, Double> sums = new TreeMap<>();
		for (JsonNode series : result) {
			for (Point point : values(series)) {
				sums.merge(DictKeys.of(sums, point.time()), 0.0 + point.value(), Double::sum);
			}
		}
		List<Point> points = new ArrayList<>();
		sums.forEach((time, value) -> points.add(new Point(time, value)));
		return points;
	}

	/**
	 * A series' mean over the range.
	 * @param series one series
	 * @return the mean of its finite values; empty when it has none
	 * @throws PrometheusException when the series has malformed points
	 */
	static OptionalDouble timeMean(JsonNode series) {
		List<Point> points = values(series);
		return points.isEmpty() ? OptionalDouble.empty()
				: OptionalDouble.of(FloatSum.of(points.stream().mapToDouble(Point::value)) / points.size());
	}

	/**
	 * A series' points with a finite value.
	 * @param series one series
	 * @return its points, in its order; none when it has no values
	 * @throws PrometheusException when a point is not a {@code [timestamp, value]} pair
	 * Python's {@code float()} reads
	 */
	static List<Point> values(JsonNode series) {
		List<Point> points = new ArrayList<>();
		JsonNode values = series.path("values");
		if (!PythonText.truthy(values)) {
			return points;
		}
		if (!values.isArray()) {
			throw malformed();
		}
		try {
			for (JsonNode point : values) {
				if (!point.isArray() || point.size() != 2) {
					throw malformed();
				}
				double value = PythonFloats.of(point.get(1));
				if (Double.isFinite(value)) {
					points.add(new Point(PythonFloats.of(point.get(0)), value));
				}
			}
		}
		catch (NumberFormatException ex) {
			throw malformed();
		}
		return points;
	}

	/**
	 * A bucket's upper bound.
	 * @param le its {@code le} label
	 * @return the bound; infinite for {@code +Inf}
	 * @throws PrometheusException when the label is no number
	 */
	private static double bound(JsonNode le) {
		if (le.isString() && List.of("+Inf", "Inf", "inf").contains(le.stringValue())) {
			return Double.POSITIVE_INFINITY;
		}
		double bound;
		try {
			bound = PythonFloats.of(le);
		}
		catch (NumberFormatException ex) {
			throw new PrometheusException(MALFORMED_LE);
		}
		if (Double.isNaN(bound)) {
			throw new PrometheusException(MALFORMED_LE);
		}
		return bound;
	}

	private static PrometheusException malformed() {
		return new PrometheusException("a series has malformed [timestamp, value] points");
	}

	/**
	 * One point of a series.
	 *
	 * @param time its timestamp, in seconds since the epoch
	 * @param value its value
	 */
	public record Point(double time, double value) {

	}

}
