package io.archsim.engine.calibration.resources;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;

/**
 * CPU per request against the rate one replica gets: {@code serviceTimeByLoad} before it
 * is turned into service-time factors.
 *
 * @param points each bin's rate per replica and CPU per request as a multiple of
 * {@code referenceMs}: rising rates, factors that never rise
 * @param referenceMs the CPU per request a factor of 1 stands for, in milliseconds
 * @param measured each bin's rate per replica and CPU per request in milliseconds, before
 * the fit that never rises
 * @param error the largest relative difference between a bin's measured CPU per request
 * and the fit; {@code null} when no bin measured any
 */
public record LoadCurve(List<RatePoint> points, double referenceMs, List<RatePoint> measured, @Nullable Double error) {

	private static final int FACTOR_DIGITS = 4;

	/**
	 * Copies the points.
	 */
	public LoadCurve {
		points = List.copyOf(points);
		measured = List.copyOf(measured);
	}

	/**
	 * How far the factors spread.
	 * @return the largest factor over the smallest, less 1; 0 when the smallest is not
	 * above 0
	 */
	public double span() {
		double smallest = this.points.stream().mapToDouble(RatePoint::value).min().orElse(0.0);
		double largest = this.points.stream().mapToDouble(RatePoint::value).max().orElse(0.0);
		return (smallest > 0) ? largest / smallest - 1.0 : 0.0;
	}

	/**
	 * The curve as {@code serviceTimeByLoad}: each bin's rate per replica to a tenth and
	 * its factor to four places. The engine scales the whole service time by the factor
	 * and charges its CPU share as CPU, so the factors are the CPU's.
	 * @return the {@code {rps, factor}} points
	 */
	public ArrayNode table() {
		ArrayNode table = JsonNodeFactory.instance.arrayNode();
		this.points.forEach((point) -> table.addObject()
			.put("rps", PythonRound.round(point.rate(), 1))
			.put("factor", PythonRound.round(point.value(), FACTOR_DIGITS)));
		return table;
	}

	/**
	 * The curve in words: CPU per request at the quietest and the busiest bin.
	 * @return the text
	 */
	public String text() {
		RatePoint quietest = this.measured.getFirst();
		RatePoint busiest = this.measured.getLast();
		return "CPU per request " + PythonFormat.format(quietest.value(), ".3g") + " ms at about "
				+ PythonFormat.format(quietest.rate(), ",.0f") + " req/s per replica, "
				+ PythonFormat.format(busiest.value(), ".3g") + " ms at about "
				+ PythonFormat.format(busiest.rate(), ",.0f") + " req/s (" + this.measured.size()
				+ " bins by rate, fitted never rising)";
	}

}
