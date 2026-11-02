package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/**
 * What a request does: a read or a write. Links carry some of them; routing follows them.
 */
public enum RequestType implements JsonEnum {

	/** A request that reads data. */
	READ("read"),
	/** A request that changes data. */
	WRITE("write");

	private final String json;

	RequestType(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
