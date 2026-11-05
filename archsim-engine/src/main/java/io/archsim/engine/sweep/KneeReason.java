package io.archsim.engine.sweep;

import io.archsim.model.codec.JsonEnum;

/** Why a load sweep's knee is where it is. */
public enum KneeReason implements JsonEnum {

	/**
	 * The design stopped keeping up: successful throughput below 95% of the offered load.
	 */
	THROUGHPUT("throughput"),

	/** The tail latency jumped. */
	LATENCY("latency");

	private final String json;

	KneeReason(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
