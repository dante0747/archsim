package io.archsim.model.component;

import io.archsim.model.codec.JsonEnum;

/**
 * How well ArchSim knows a technology: core ones are validated first, the rest are
 * experimental.
 */
public enum Tier implements JsonEnum {

	/** Validated first, against measurements where there are any. */
	CORE("core"),
	/** Based on documentation and illustrative numbers. */
	EXPERIMENTAL("experimental");

	private final String json;

	Tier(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
