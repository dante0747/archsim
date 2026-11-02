package io.archsim.model.component.cache;

import io.archsim.model.codec.JsonEnum;

/** Whether a cache snapshots its data to disk; auto follows the technology. */
public enum Persistence implements JsonEnum {

	/** Whatever the technology does by default. */
	AUTO("auto"),
	/** The data lives in memory only. */
	NONE("none"),
	/** The data is snapshotted to disk at an interval, each fork pausing commands. */
	RDB("rdb");

	private final String json;

	Persistence(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
