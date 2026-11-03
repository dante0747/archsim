package io.archsim.app.reviews;

import io.archsim.model.codec.JsonEnum;

/**
 * What a comment is on.
 */
enum AnchorKind implements JsonEnum {

	/** A part of the design, by its id. */
	NODE("node"),

	/** A link between parts, by its id. */
	EDGE("edge"),

	/** The project as a whole. */
	PROJECT("project"),

	/** One of its runs. */
	RUN("run");

	private final String json;

	AnchorKind(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
