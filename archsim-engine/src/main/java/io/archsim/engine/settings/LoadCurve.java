package io.archsim.engine.settings;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;

import io.archsim.model.component.service.LoadPoint;
import io.archsim.model.text.PythonFormat;

/**
 * A service's time per request as its load changes ({@code serviceTimeByLoad}): at each
 * rate one replica receives, the configured service time times a factor, interpolated in
 * log(rate) between points and the nearest point's factor outside them, never
 * extrapolated.
 */
public final class LoadCurve {

	/** A curve without points: the factor is 1 at every rate. */
	public static final LoadCurve FLAT = new LoadCurve(new TreeMap<>());

	private static final double MILLIS_PER_SECOND = 1000;

	/** How the words write a factor. */
	private static final String FACTOR = ".3g";

	/** How the words write a rate. */
	private static final String RATE = ",.0f";

	/** How many times the search for a capacity halves its range. */
	private static final int HALVINGS = 60;

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
	 * @return the factor; the last point's for a rate that is not a number, as in Python
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
		if (rps >= last.getKey() || Double.isNaN(rps)) {
			// a rate that is not a number fails every comparison in Python's load_factor,
			// which then gives the last point's factor
			return last.getValue();
		}
		Map.Entry<Double, Double> below = this.points.lowerEntry(rps);
		Map.Entry<Double, Double> above = this.points.ceilingEntry(rps);
		double position = StrictMath.log(rps / below.getKey()) / StrictMath.log(above.getKey() / below.getKey());
		return below.getValue() + (above.getValue() - below.getValue()) * position;
	}

	/**
	 * The rate one replica can serve when it could serve {@code baseRps} at factor 1: the
	 * rate at which {@code rate × factorAt(rate)} reaches {@code baseRps}, as its CPU
	 * fills at that rate's cost. The busy time grows with the rate, so halving the range
	 * in log(rate) 60 times finds the one crossing.
	 * @param baseRps the rate one replica serves at factor 1
	 * @return the rate it serves; {@code baseRps} without points, zero without a rate
	 */
	public double capacityRps(double baseRps) {
		if (this.points.isEmpty() || baseRps <= 0) {
			return (baseRps > 0) ? baseRps : 0.0;
		}
		return rateFilling(MILLIS_PER_SECOND / baseRps, MILLIS_PER_SECOND, 0.0);
	}

	/**
	 * The rate at which {@code rate × (workMs × factorAt(rate) + fixedMs)} reaches a
	 * budget: where a replica's cores (a thousand milliseconds a second each) or its
	 * slots fill, when each request needs work that follows the curve and time that does
	 * not. The busy time grows with the rate, so halving the range in log(rate) 60 times
	 * finds the one crossing.
	 * @param workMs the work per request at factor 1
	 * @param budgetMs the busy time there is per second
	 * @param fixedMs the time per request that does not follow the curve
	 * @return requests per second; infinite when a request takes no time
	 */
	public double rateFilling(double workMs, double budgetMs, double fixedMs) {
		if (workMs <= 0 && fixedMs <= 0) {
			return Double.POSITIVE_INFINITY;
		}
		if (this.points.isEmpty()) {
			return budgetMs / (workMs + fixedMs);
		}
		double low = budgetMs / (workMs * Collections.max(this.points.values()) + fixedMs);
		double high = budgetMs / (workMs * Collections.min(this.points.values()) + fixedMs);
		for (int i = 0; i < HALVINGS; i++) {
			double middle = Math.sqrt(low * high);
			if (middle * (workMs * factorAt(middle) + fixedMs) < budgetMs) {
				low = middle;
			}
			else {
				high = middle;
			}
		}
		return high;
	}

	/**
	 * The curve in words, such as
	 * {@code ×1.8 at 50 req/s to ×1 at 1,674 req/s (3 points)}.
	 * @return the words; {@code no load curve} without points
	 */
	public String describe() {
		if (this.points.isEmpty()) {
			return "no load curve";
		}
		Map.Entry<Double, Double> first = this.points.firstEntry();
		if (this.points.size() == 1) {
			return "×" + PythonFormat.format(first.getValue(), FACTOR) + " at every rate";
		}
		Map.Entry<Double, Double> last = this.points.lastEntry();
		return "×" + PythonFormat.format(first.getValue(), FACTOR) + " at " + PythonFormat.format(first.getKey(), RATE)
				+ " req/s to ×" + PythonFormat.format(last.getValue(), FACTOR) + " at "
				+ PythonFormat.format(last.getKey(), RATE) + " req/s (" + this.points.size() + " points)";
	}

	private static void add(NavigableMap<Double, Double> sorted, double rps, double factor) {
		if (rps > 0 && factor > 0 && Double.isFinite(rps) && Double.isFinite(factor)) {
			sorted.put(rps, factor);
		}
	}

}
