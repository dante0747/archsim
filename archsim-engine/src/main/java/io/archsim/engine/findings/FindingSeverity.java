package io.archsim.engine.findings;

import io.archsim.model.codec.JsonEnum;

/** How much a run's finding matters. */
public enum FindingSeverity implements JsonEnum {

	/** The run failed its users: a bottleneck, most requests lost. */
	CRITICAL("critical"),

	/** Worth acting on. */
	WARNING("warning"),

	/** Worth knowing. */
	INFO("info"),

	/** All is well. */
	OK("ok");

	private final String json;

	FindingSeverity(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
