package io.archsim.engine.provenance;

import io.archsim.model.codec.JsonEnum;

/** What an input belongs to. */
public enum InputScope implements JsonEnum {

	/** A setting of a part. */
	NODE("node"),

	/** A setting of a link. */
	EDGE("edge"),

	/** A field of the workload. */
	WORKLOAD("workload");

	private final String json;

	InputScope(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
