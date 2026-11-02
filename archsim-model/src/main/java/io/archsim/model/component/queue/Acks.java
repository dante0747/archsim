package io.archsim.model.component.queue;

import io.archsim.model.codec.JsonEnum;

/** What a publish waits for before it is acknowledged; auto follows the technology. */
public enum Acks implements JsonEnum {

	/** Whatever the technology does by default. */
	AUTO("auto"),
	/** A publish is acknowledged once the leader has the message. */
	LEADER("leader"),
	/** A publish waits until the replicas have the message. */
	ALL("all");

	private final String json;

	Acks(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
