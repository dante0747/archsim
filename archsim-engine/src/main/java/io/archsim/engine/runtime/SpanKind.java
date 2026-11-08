package io.archsim.engine.runtime;

import io.archsim.model.codec.JsonEnum;

/**
 * What a step of a traced request was, as a report's trace names it.
 */
public enum SpanKind implements JsonEnum {

	/** Crossing a link, one way. */
	NETWORK("network"),

	/** Handled by a part, its downstream calls included. */
	HANDLE("handle"),

	/** Refused by a part. */
	REJECTED("rejected"),

	/** The moment a caller gave up. */
	TIMEOUT("timeout"),

	/** Waiting before a retry. */
	BACKOFF("backoff"),

	/** A call to a third-party API. */
	EXTERNAL("external");

	private final String json;

	SpanKind(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
