package io.archsim.engine.calibration;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.calibration.otlp.OtlpMapping;
import io.archsim.engine.simulation.SimulationLimits;

/**
 * What a calibration from OpenTelemetry traces calibrates, from what, and how.
 *
 * @param document the design; it is not changed
 * @param traces the OTLP JSON export
 * @param mapping which part each service and database is, and the CPU-time attribute;
 * {@code null} to match parts by their ids and labels alone
 * @param minSamples the fewest spans a time is fitted from
 * @param limits the replay run's limits; {@code null} for none
 */
public record OtlpRequest(JsonNode document, JsonNode traces, @Nullable OtlpMapping mapping, int minSamples,
		@Nullable SimulationLimits limits) {

	/** The fewest spans a time is fitted from, unless asked otherwise. */
	public static final int DEFAULT_MIN_SAMPLES = 20;

	/**
	 * Copies the design and the traces.
	 */
	public OtlpRequest {
		document = document.deepCopy();
		traces = traces.deepCopy();
	}

	@Override
	public JsonNode document() {
		return this.document.deepCopy();
	}

	@Override
	public JsonNode traces() {
		return this.traces.deepCopy();
	}

}
