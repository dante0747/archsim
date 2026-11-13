package io.archsim.benchmarks.inputs;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.benchmarks.values.PythonValues;

/**
 * What a calibration read: a pass by its name, a timing the service reported, a
 * container's CPU net of what it burns at rest, and the extra of a resource per extra
 * unit of work between two passes. A pass or a timing today's derivation needs and the
 * calibration lacks is a {@link MissingReadingException}.
 */
final class PassReadings {

	private PassReadings() {
	}

	/**
	 * A timing a service reported in a pass.
	 * @param stats the service's {@code /stats}
	 * @param name the timing's name
	 * @return the timing: its count, mean, median, p95 and p99
	 * @throws MissingReadingException when the pass recorded none, which today's
	 * derivation cannot do without
	 */
	static JsonNode timing(JsonNode stats, String name) {
		JsonNode timing = stats.path("timings").path(name);
		if (!timing.isObject() || timing.isEmpty()) {
			throw new MissingReadingException("calibration recorded no '" + name + "' timings");
		}
		return timing;
	}

	/**
	 * A calibration pass by its name.
	 * @param passes the calibration's passes
	 * @param name the pass's name: its mix and rate, {@code write_async:1}
	 * @return the pass
	 * @throws MissingReadingException when the calibration ran none, which today's
	 * derivation cannot do without
	 */
	static JsonNode pass(JsonNode passes, String name) {
		JsonNode pass = passes.path(name);
		if (!pass.isObject()) {
			throw new MissingReadingException("calibration recorded no '" + name + "' pass");
		}
		return pass;
	}

	/**
	 * The extra of a resource per extra unit of work between two passes of equal length.
	 * @param low the resource in the lower pass, or None
	 * @param high the resource in the higher pass, or None
	 * @param lowWork the work of the lower pass
	 * @param highWork the work of the higher pass
	 * @return the slope; {@code null} without both readings or more work in the higher
	 * pass
	 */
	static @Nullable Double slope(JsonNode low, JsonNode high, double lowWork, double highWork) {
		if (PythonValues.isNone(low) || PythonValues.isNone(high) || highWork <= lowWork) {
			return null;
		}
		return (high.doubleValue() - low.doubleValue()) / (highWork - lowWork);
	}

	/**
	 * A container's CPU in a pass less what it burns at rest over the pass (protocol v5).
	 * @param entry the pass
	 * @param idle the idle pass
	 * @param container the container
	 * @return its CPU (ms), at least zero; {@code null} when the pass did not read it
	 */
	static @Nullable Double netCpuMs(JsonNode entry, JsonNode idle, String container) {
		JsonNode used = entry.path("containerCpuMs").path(container);
		if (PythonValues.isNone(used)) {
			return null;
		}
		JsonNode rest = idle.path("containerCpuMsPerSecond").path(container);
		double atRest = PythonValues.isNone(rest) ? 0.0 : rest.doubleValue();
		return Math.max(0.0, used.doubleValue() - atRest * Protocol.CALIBRATION_S);
	}

}
