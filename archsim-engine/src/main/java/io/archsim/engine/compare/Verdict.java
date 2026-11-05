package io.archsim.engine.compare;

import io.archsim.model.codec.JsonEnum;

/** How a figure changed from the first run to the second. */
public enum Verdict implements JsonEnum {

	/** It changed the better way. */
	BETTER("better"),

	/** It changed the worse way. */
	WORSE("worse"),

	/** It changed by less than a comparison counts. */
	SAME("same"),

	/** It changed, and neither way is better. */
	CHANGED("changed"),

	/** A run did not measure it. */
	NOT_AVAILABLE("n/a");

	private final String json;

	Verdict(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * Whether the figure changed one way or the other, better or worse.
	 * @return whether it did
	 */
	boolean judged() {
		return this == BETTER || this == WORSE;
	}

}
