package io.archsim.model.component.queue;

import io.archsim.model.codec.JsonEnum;

/** Whether a failed message is delivered again; auto follows the technology. */
public enum Delivery implements JsonEnum {

	/** Whatever the technology does by default. */
	AUTO("auto"),
	/** No message is lost: a full backlog refuses publishes. */
	AT_LEAST_ONCE("at_least_once"),
	/**
	 * Overflowing messages are dropped, and the publisher still gets an acknowledgement.
	 */
	AT_MOST_ONCE("at_most_once");

	private final String json;

	Delivery(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
