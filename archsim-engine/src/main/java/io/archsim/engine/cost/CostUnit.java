package io.archsim.engine.cost;

import io.archsim.model.codec.JsonEnum;

/** What a cost line's unit price is per. */
public enum CostUnit implements JsonEnum {

	/** An instance-hour. */
	HOUR("hour"),

	/** A million requests, calls or units. */
	MILLION("million");

	private final String json;

	CostUnit(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
