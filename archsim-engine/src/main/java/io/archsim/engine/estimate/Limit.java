package io.archsim.engine.estimate;

import io.archsim.model.codec.JsonEnum;

/** What limits a part first. */
public enum Limit implements JsonEnum {

	/** Its slots: workers, connections, executors or consumers. */
	SLOTS("slots"),

	/** Its CPU cores. */
	CPU("cpu"),

	/** A rate or throughput limit, past which it turns requests away. */
	RATE_LIMIT("rate limit");

	private final String json;

	Limit(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
