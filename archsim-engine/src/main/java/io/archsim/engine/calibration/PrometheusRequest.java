package io.archsim.engine.calibration;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.calibration.mapping.CalibrationMapping;
import io.archsim.engine.calibration.mapping.TimeRange;
import io.archsim.engine.calibration.prometheus.PrometheusClient;
import io.archsim.engine.simulation.SimulationLimits;

/**
 * What a calibration from Prometheus calibrates, from what, and how.
 *
 * @param document the design; it is not changed
 * @param mapping which telemetry sets which input
 * @param client where the queries are answered
 * @param range the range of time read; {@code null} for the mapping's (a recording's own
 * range wins over both)
 * @param replay whether the calibrated design is simulated and checked against what was
 * observed
 * @param limits the replay run's limits; {@code null} for none
 */
public record PrometheusRequest(JsonNode document, CalibrationMapping mapping, PrometheusClient client,
		@Nullable TimeRange range, boolean replay, @Nullable SimulationLimits limits) {

	/**
	 * Copies the design.
	 */
	public PrometheusRequest {
		document = document.deepCopy();
	}

	@Override
	public JsonNode document() {
		return this.document.deepCopy();
	}

}
