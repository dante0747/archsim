package io.archsim.engine.settings;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;

import io.archsim.model.component.service.LoadPoint;

/**
 * A service's time per request as its load changes ({@code serviceTimeByLoad}): at each
 * rate one replica receives, the configured service time times a factor, interpolated in
 * log(rate) between points and the nearest point's factor outside them, never
 * extrapolated.
 */
public final class LoadCurve {

	/** A curve without points: the factor is 1 at every rate. */
	public static final LoadCurve FLAT = new LoadCurve(new TreeMap<>());

	private final NavigableMap<Double, Double> points;

	private LoadCurve(NavigableMap<Double, Double> points) {
		this.points = points;
	}

	/**
	 * A service's curve, from its config record.
	 * @param points its points, in any order
	 * @return the curve; of two points at one rate, the later
	 */
	public static LoadCurve of(List<LoadPoint> points) {
		TreeMap<Double, Double> sorted = new TreeMap<>();
		points.forEach((point) -> add(sorted, point.rps(), point.factor()));
		return new LoadCurve(sorted);
	}

	/**
	 * A curve read from JSON as a design that may not be valid yet is read (ADR-0038):
	 * points without a positive, finite rate and factor are skipped.
	 * @param points the {@code serviceTimeByLoad} list
	 * @return the curve
	 */
	static LoadCurve ofJson(JsonNode points) {
		TreeMap<Double, Double> sorted = new TreeMap<>();
		for (JsonNode point : points) {
			JsonNode rps = point.path("rps");
			JsonNode factor = point.path("factor");
			if (rps.isNumber() && factor.isNumber()) {
				add(sorted, rps.doubleValue(), factor.doubleValue());
			}
		}
		return new LoadCurve(sorted);
	}

	/**
	 * Whether the curve has points.
	 * @return {@code false} for a flat curve
	 */
	public boolean isEmpty() {
		return this.points.isEmpty();
	}

	/**
	 * The service-time factor at a rate.
	 * @param rps the requests per second one replica receives
	 * @return the factor
	 */
	public double factorAt(double rps) {
		if (this.points.isEmpty()) {
			return 1.0;
		}
		Map.Entry<Double, Double> first = this.points.firstEntry();
		Map.Entry<Double, Double> last = this.points.lastEntry();
		if (rps <= first.getKey()) {
			return first.getValue();
		}
		if (rps >= last.getKey()) {
			return last.getValue();
		}
		Map.Entry<Double, Double> below = this.points.lowerEntry(rps);
		Map.Entry<Double, Double> above = this.points.ceilingEntry(rps);
		double position = Math.log(rps / below.getKey()) / Math.log(above.getKey() / below.getKey());
		return below.getValue() + (above.getValue() - below.getValue()) * position;
	}

	private static void add(NavigableMap<Double, Double> sorted, double rps, double factor) {
		if (rps > 0 && factor > 0 && Double.isFinite(rps) && Double.isFinite(factor)) {
			sorted.put(rps, factor);
		}
	}

}
