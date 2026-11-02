package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/** How requests arrive: at random (exponential gaps) or evenly spaced. */
public enum ArrivalProcess implements JsonEnum {

	/** Random arrivals: exponential gaps between requests. */
	POISSON("poisson"),
	/** Evenly spaced arrivals: the same gap between every two requests. */
	DETERMINISTIC("deterministic");

	private final String json;

	ArrivalProcess(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
