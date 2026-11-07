package io.archsim.engine.calibration.mapping;

import io.archsim.model.codec.JsonEnum;

/**
 * An input of the workload that telemetry can set.
 */
public enum WorkloadInput implements JsonEnum {

	/** The arrival rate, in requests per second. */
	RPS("rps");

	private final String json;

	WorkloadInput(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
