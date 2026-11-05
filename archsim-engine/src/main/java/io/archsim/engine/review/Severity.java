package io.archsim.engine.review;

import io.archsim.model.codec.JsonEnum;

/** How much a finding matters, from the most to the least. */
public enum Severity implements JsonEnum {

	/** Likely to take requests down or overload a part. */
	HIGH("high"),

	/** A risk to fix before it matters. */
	MEDIUM("medium"),

	/** Worth knowing. */
	LOW("low");

	private final String json;

	Severity(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
