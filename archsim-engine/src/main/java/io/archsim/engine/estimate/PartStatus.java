package io.archsim.engine.estimate;

import io.archsim.model.codec.JsonEnum;

/** How a part fares at the peak. */
public enum PartStatus implements JsonEnum {

	/** No estimate: its load depends on the run, or it has no capacity model. */
	UNKNOWN("unknown"),

	/** It keeps up with room to spare. */
	OK("ok"),

	/** It keeps up, at 80% or more of a limit. */
	BUSY("busy"),

	/** It cannot keep up. */
	OVERLOADED("overloaded");

	private final String json;

	PartStatus(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
