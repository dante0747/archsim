package io.archsim.app.tokens;

import io.archsim.model.codec.JsonEnum;

/**
 * What a token may do.
 */
enum TokenScope implements JsonEnum {

	/** Viewer everywhere its person has a role. */
	READ("read"),

	/** Its person's own role. */
	WRITE("write");

	private final String json;

	TokenScope(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
