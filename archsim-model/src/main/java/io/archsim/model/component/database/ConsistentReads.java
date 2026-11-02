package io.archsim.model.component.database;

import io.archsim.model.codec.JsonEnum;

/** Whether reads of a metered table are eventually or strongly consistent. */
public enum ConsistentReads implements JsonEnum {

	/** Eventually consistent reads, at half a unit each. */
	EVENTUAL("eventual"),
	/** Strongly consistent reads, at a full unit each. */
	STRONG("strong");

	private final String json;

	ConsistentReads(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
