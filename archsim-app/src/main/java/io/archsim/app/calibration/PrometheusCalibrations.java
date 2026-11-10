package io.archsim.app.calibration;

import java.time.Clock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.CalibrationOutcome;
import io.archsim.engine.calibration.PrometheusCalibration;
import io.archsim.engine.calibration.PrometheusRequest;
import io.archsim.engine.calibration.ReplaySimulator;
import io.archsim.engine.calibration.mapping.CalibrationMapping;
import io.archsim.engine.calibration.mapping.MappingLoader;
import io.archsim.engine.calibration.mapping.TimeRange;
import io.archsim.engine.calibration.prometheus.PrometheusClient;
import io.archsim.engine.calibration.prometheus.RecordedPrometheus;
import io.archsim.engine.calibration.prometheus.http.HttpPrometheus;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.model.component.ComponentRegistry;

/**
 * A calibration from Prometheus, as the Python API's {@code _calibrate_prometheus} ran
 * it: the mapping, then recorded responses or a server the policy lets it call, then the
 * range.
 */
final class PrometheusCalibrations {

	/** The seconds between samples when neither the request nor the mapping says. */
	private static final double DEFAULT_STEP = 60;

	private final PrometheusCalibration calibration;

	/**
	 * Calibrations over these types.
	 * @param registry the design's types
	 * @param replay what the calibrated design is replayed with
	 * @param clock when the provenance says the inputs were fitted
	 */
	PrometheusCalibrations(ComponentRegistry registry, ReplaySimulator replay, Clock clock) {
		this.calibration = new PrometheusCalibration(registry, replay, clock);
	}

	/**
	 * Calibrates a design from Prometheus.
	 * @param request the design, the mapping and the telemetry
	 * @param outbound which Prometheus servers it may call
	 * @param replay the limits of the replay that checks the result
	 * @return the outcome
	 * @throws io.archsim.app.web.ApiException {@code 422} without a mapping, without a
	 * server or recorded responses, or with a server the policy refuses
	 */
	CalibrationOutcome calibrate(CalibrateRequest request, OutboundPolicy outbound, SimulationLimits replay) {
		JsonNode source = request.mapping();
		if (source == null || source.isNull()) {
			throw CalibrationRefusals.unreadable("A Prometheus calibration needs a mapping");
		}
		CalibrationMapping mapping = source.isString() ? MappingLoader.load(source.stringValue())
				: MappingLoader.load(source);
		ObjectNode recorded = request.recorded();
		if (recorded != null) {
			return calibrate(request, mapping, RecordedPrometheus.of(recorded, ""), replay);
		}
		String url = request.prometheusUrl();
		if (url == null || url.isEmpty()) {
			throw CalibrationRefusals.unreadable("Give a Prometheus URL or recorded responses");
		}
		try (HttpPrometheus client = HttpPrometheus.of(outbound.serverFor(url))) {
			return calibrate(request, mapping, client, replay);
		}
	}

	private CalibrationOutcome calibrate(CalibrateRequest request, CalibrationMapping mapping, PrometheusClient client,
			SimulationLimits replay) {
		double step = DEFAULT_STEP;
		TimeRange mapped = mapping.range();
		Double asked = request.step();
		if (asked != null) {
			step = asked;
		}
		else if (mapped != null) {
			step = mapped.step();
		}
		String text = request.range();
		TimeRange range = (text != null && !text.isEmpty()) ? TimeRange.parse(text, step) : null;
		return this.calibration
			.calibrate(new PrometheusRequest(request.document(), mapping, client, range, request.replay(), replay));
	}

}
