package io.archsim.model.document;

import io.archsim.model.codec.JsonEnum;

/** Whether auto-sizing may pick burstable instance types (AWS t families). */
public enum Burstable implements JsonEnum {

	/**
	 * A light part may run on a burstable type when its load fits the type's baseline.
	 */
	AUTO("auto"),
	/** No part runs on a burstable type. */
	NEVER("never");

	private final String json;

	Burstable(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
