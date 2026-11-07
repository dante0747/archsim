package io.archsim.engine.calibration.resources;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * How CPU per request continues above a load curve's top rate.
 *
 * @param topRps the top rate, in requests per second per replica
 * @param topMs the top rate's CPU per request, the median of its passes, in milliseconds
 * @param busy the share of the replica's CPUs the top rate kept busy
 * @param slope the fitted change of CPU per request, in milliseconds, per unit of
 * {@code ln(rate)}
 * @param error the slope's standard error, from the passes' scatter around the fit;
 * {@code null} without scatter
 * @param points the rate and CPU per request where the extension ends; none when the
 * curve stays flat above the top
 * @param reason why it was extended or not, in words
 */
public record CurveExtension(double topRps, double topMs, double busy, double slope, @Nullable Double error,
		List<RatePoint> points, String reason) {

	/**
	 * Copies the points.
	 */
	public CurveExtension {
		points = List.copyOf(points);
	}

	/**
	 * The points as multiples of the top rate's CPU per request.
	 * @return each point's rate and factor
	 */
	public List<RatePoint> factors() {
		return this.points.stream().map((point) -> new RatePoint(point.rate(), point.value() / this.topMs)).toList();
	}

}
