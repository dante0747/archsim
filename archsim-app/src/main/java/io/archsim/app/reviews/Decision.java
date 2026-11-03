package io.archsim.app.reviews;

import io.archsim.model.codec.JsonEnum;

/**
 * A reviewer's verdict on a run.
 */
enum Decision implements JsonEnum {

	/** The design, as this run simulated it, is good. */
	APPROVED("approved"),

	/** The design needs changes first. */
	CHANGES_REQUESTED("changes_requested");

	private final String json;

	Decision(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
