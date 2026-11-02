package io.archsim.model.component.database;

import io.archsim.model.codec.JsonEnum;

/** How many of a key's replicas an operation waits for: one, a quorum, or all. */
public enum Consistency implements JsonEnum {

	/** An operation waits for one replica. */
	ONE("one"),
	/** An operation waits for a majority of the replicas. */
	QUORUM("quorum"),
	/** An operation waits for every replica. */
	ALL("all");

	private final String json;

	Consistency(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
