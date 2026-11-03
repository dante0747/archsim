package io.archsim.app.calibration;

import io.archsim.model.codec.JsonEnum;

/**
 * The telemetry a calibration reads.
 */
enum CalibrationSource implements JsonEnum {

	/** A Prometheus server's metrics, queried or recorded. */
	PROMETHEUS("prometheus"),

	/** OpenTelemetry traces, as OTLP JSON. */
	OTLP("otlp");

	private final String json;

	CalibrationSource(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
