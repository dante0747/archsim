package io.archsim.engine.calibration.resources;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A part's {@code cpuShare} fitted from its CPU in use against its rate of requests.
 *
 * @param share CPU per request over the mean service time, at most 1
 * @param cpuMs the CPU per request, in milliseconds, rate-weighted over the range
 * @param serviceMs the mean service time, in milliseconds
 * @param capped whether CPU per request exceeded the service time, so that the share is 1
 * and the model under-counts CPU
 * @param error the largest relative error of the fitted CPU at and above the median rate;
 * {@code null} when none was measured there
 * @param curve CPU per request against the rate per replica; {@code null} when it cannot
 * be fitted
 * @param points how many points the fit rests on
 * @param notes what the fit leaves out, in words
 */
public record CpuFit(double share, double cpuMs, double serviceMs, boolean capped, @Nullable Double error,
		@Nullable LoadCurve curve, int points, List<String> notes) {

	/**
	 * Copies the notes.
	 */
	public CpuFit {
		notes = List.copyOf(notes);
	}

}
