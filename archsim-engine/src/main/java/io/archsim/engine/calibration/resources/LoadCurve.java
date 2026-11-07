package io.archsim.engine.calibration.resources;

import java.util.List;

import org.jspecify.annotations.Nullable;

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

	/**
	 * Copies the points.
	 */
	public LoadCurve {
		points = List.copyOf(points);
		measured = List.copyOf(measured);
	}

}
