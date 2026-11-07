package io.archsim.engine.calibration.fit;

import io.archsim.model.codec.JsonEnum;

/**
 * What measured latencies are fitted to.
 */
public enum FitKind implements JsonEnum {

	/** A lognormal, whose median and p95 match the observed ones exactly. */
	LOGNORMAL("lognormal"),

	/** An empirical quantile table, linear between its points. */
	EMPIRICAL("empirical");

	private final String json;

	FitKind(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
