package io.archsim.engine.cost;

import io.archsim.model.codec.JsonEnum;

/** Where a part's price per instance-hour came from, the first that applies. */
public enum PriceBasis implements JsonEnum {

	/** A price a person set on the part. */
	SET("set"),

	/** A price snapshot's instance type, or its rate for a managed part. */
	SNAPSHOT("snapshot"),

	/** A technology preset's illustrative price. */
	PRESET("preset"),

	/** The part's size at generic illustrative rates. */
	SIZE("size"),

	/** The catalog default. */
	DEFAULT("default");

	private final String json;

	PriceBasis(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
