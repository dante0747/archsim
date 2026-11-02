package io.archsim.model.issue;

import io.archsim.model.codec.JsonEnum;

/** How bad an issue is: an error stops a simulation, a warning does not. */
public enum Severity implements JsonEnum {

	/** The design cannot be simulated until the issue is fixed. */
	ERROR("error"),

	/** The design can be simulated, but the issue is worth knowing about. */
	WARNING("warning");

	private final String json;

	Severity(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
