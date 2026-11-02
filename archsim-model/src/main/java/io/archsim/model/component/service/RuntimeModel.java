package io.archsim.model.component.service;

import io.archsim.model.codec.JsonEnum;

/**
 * How a service's handlers share work: a thread per request, or event loops; auto follows
 * the technology.
 */
public enum RuntimeModel implements JsonEnum {

	/** Whatever the technology does by default. */
	AUTO("auto"),
	/** Each worker handles one request at a time. */
	THREAD_POOL("thread_pool"),
	/**
	 * Each process runs every request's CPU work on one thread and waits for I/O without
	 * blocking.
	 */
	EVENT_LOOP("event_loop");

	private final String json;

	RuntimeModel(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
