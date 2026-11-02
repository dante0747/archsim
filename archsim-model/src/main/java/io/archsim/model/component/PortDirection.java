package io.archsim.model.component;

import io.archsim.model.codec.JsonEnum;

/** Whether a port receives requests or sends them. */
public enum PortDirection implements JsonEnum {

	/** The port receives requests. */
	IN("in"),
	/** The port sends requests. */
	OUT("out");

	private final String json;

	PortDirection(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
