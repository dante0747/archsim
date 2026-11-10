package io.archsim.cli;

import io.archsim.model.codec.JsonEnum;

/**
 * What {@code archsim check} writes its result as.
 */
enum CheckFormat implements JsonEnum {

	/** The pull-request comment, as the GitHub Action posts it. */
	MARKDOWN("markdown"),

	/** The whole result, as {@code json.dumps(result, indent=2)} wrote it. */
	JSON("json");

	private final String json;

	CheckFormat(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
