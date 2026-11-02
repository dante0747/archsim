package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/** What a calibration replay check compares: the whole request path, or one component. */
public enum ReplayScope implements JsonEnum {

	/** The whole request path, as the clients see it. */
	END_TO_END("end_to_end"),
	/** One component, named by the check's id. */
	COMPONENT("component");

	private final String json;

	ReplayScope(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
