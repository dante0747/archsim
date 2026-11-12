package io.archsim.app.calibration;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.IgnoreUnknownKeys;
import io.archsim.model.codec.Le;

/**
 * What {@code POST /calibrate} takes.
 *
 * @param document the design to calibrate
 * @param source {@code prometheus} or {@code otlp}
 * @param mapping YAML text or an object (see docs/calibration.md); never a path
 * @param prometheusUrl the Prometheus server to query, such as
 * {@code http://localhost:9090}
 * @param recorded recorded responses ({@code {responses: {query: response}}}) instead of
 * a URL
 * @param range {@code START/END} in ISO 8601
 * @param step seconds between samples (default: the mapping's, else 60)
 * @param traces OTLP JSON ({@code resourceSpans})
 * @param replay whether to check the result by replaying it
 */
@IgnoreUnknownKeys
record CalibrateRequest(ObjectNode document, CalibrationSource source, @Nullable JsonNode mapping,
		@Nullable String prometheusUrl, @Nullable ObjectNode recorded, @Nullable String range,
		@Gt(0) @Le(86_400) @Nullable Double step, @Nullable ObjectNode traces, @Default("true") boolean replay) {

}
