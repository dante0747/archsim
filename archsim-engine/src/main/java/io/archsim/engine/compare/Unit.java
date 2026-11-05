package io.archsim.engine.compare;

import io.archsim.model.codec.JsonEnum;

/** The unit of a run figure a comparison puts side by side. */
public enum Unit implements JsonEnum {

	/** Requests per second. */
	REQUESTS_PER_SECOND("req/s"),

	/** A share, from 0 to 1. */
	RATIO("ratio"),

	/** Milliseconds. */
	MILLISECONDS("ms"),

	/** A multiple, such as attempts per request. */
	TIMES("x");

	private final String json;

	Unit(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
