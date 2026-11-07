package io.archsim.app.calibration;

import java.time.Clock;
import java.util.Objects;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.engine.EngineService;
import io.archsim.app.web.ApiException;
import io.archsim.app.web.JsonText;
import io.archsim.engine.calibration.CalibrationOutcome;
import io.archsim.engine.calibration.OtlpCalibration;
import io.archsim.engine.calibration.OtlpRequest;
import io.archsim.engine.calibration.ReplaySummary;
import io.archsim.engine.calibration.otlp.OtlpMappings;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.model.component.ComponentRegistry;

/**
 * Runs a calibration request as the Python API ran it: the engine's calibration from
 * Prometheus or from OpenTelemetry traces, whose replay the engine service simulates
 * (ADR-0045). What it cannot use is refused with {@code 422} in the Python API's words.
 */
@Component
class Calibrator {

	private final PrometheusCalibrations prometheus;

	private final OtlpCalibration otlp;

	private final JsonMapper json;

	Calibrator(ComponentRegistry registry, EngineService engine, Clock clock, JsonMapper json) {
		this.prometheus = new PrometheusCalibrations(registry, engine, clock);
		this.otlp = new OtlpCalibration(engine, clock);
		this.json = json;
	}

	/**
	 * Calibrates a design.
	 * @param request the design, the telemetry and how to read it
	 * @param outbound which Prometheus servers it may call
	 * @param replay the limits of the replay that checks the result
	 * @return the calibrated design, the changes, notes, the calibration record and its
	 * summary
	 * @throws ApiException {@code 422} for a design, mapping or telemetry that cannot be
	 * used, or a replay over its limits
	 */
	JsonText calibrate(CalibrateRequest request, OutboundPolicy outbound, SimulationLimits replay) {
		CalibrationOutcome outcome = CalibrationRefusals.run(() -> (request.source() == CalibrationSource.PROMETHEUS)
				? this.prometheus.calibrate(request, outbound, replay) : fromTraces(request, replay));
		ObjectNode answer = outcome.toJson();
		answer.put("summary", ReplaySummary.of(outcome.document().get("calibration")).orElse(null));
		return new JsonText(this.json.writeValueAsString(answer));
	}

	private CalibrationOutcome fromTraces(CalibrateRequest request, SimulationLimits replay) {
		JsonNode traces = request.traces();
		if (traces == null || traces.isNull()) {
			throw CalibrationRefusals.unreadable("An OpenTelemetry calibration needs the traces (OTLP JSON)");
		}
		JsonNode mapping = Objects.requireNonNullElse(request.mapping(), this.json.nullNode());
		return this.otlp.calibrate(new OtlpRequest(request.document(), traces, OtlpMappings.load(mapping).orElse(null),
				OtlpRequest.DEFAULT_MIN_SAMPLES, replay));
	}

}
