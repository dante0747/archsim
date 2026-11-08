package io.archsim.engine.settings;

import java.util.Collections;
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

	private static final double MILLIS_PER_SECOND = 1000;

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
		double workMs = MILLIS_PER_SECOND / baseRps;
		double low = MILLIS_PER_SECOND / (workMs * Collections.max(this.points.values()));
		double high = MILLIS_PER_SECOND / (workMs * Collections.min(this.points.values()));
		for (int i = 0; i < HALVINGS; i++) {
			double middle = Math.sqrt(low * high);
			if (middle * (workMs * factorAt(middle)) < MILLIS_PER_SECOND) {
				low = middle;
			}
			else {
				high = middle;
			}
		}
		return high;
	}

	private static void add(NavigableMap<Double, Double> sorted, double rps, double factor) {
		if (rps > 0 && factor > 0 && Double.isFinite(rps) && Double.isFinite(factor)) {
			sorted.put(rps, factor);
		}
	}

}
