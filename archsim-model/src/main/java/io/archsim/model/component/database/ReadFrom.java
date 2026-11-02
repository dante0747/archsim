package io.archsim.model.component.database;

import io.archsim.model.codec.JsonEnum;

/** Which instances serve reads; auto follows the technology. */
public enum ReadFrom implements JsonEnum {

	/** Whatever the technology does by default. */
	AUTO("auto"),
	/** The primary and its replicas take turns serving reads. */
	ALL("all"),
	/** Only the primary serves reads. */
	PRIMARY("primary"),
	/** Only the replicas serve reads. */
	REPLICAS("replicas");

	private final String json;

	ReadFrom(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
