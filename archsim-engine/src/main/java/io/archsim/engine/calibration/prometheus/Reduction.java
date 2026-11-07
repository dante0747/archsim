package io.archsim.engine.calibration.prometheus;

import io.archsim.model.codec.JsonEnum;

/**
 * How a series over a range becomes one figure.
 */
public enum Reduction implements JsonEnum {

	/** The mean over the range. */
	MEAN("mean"),

	/** The highest value. */
	MAX("max"),

	/** The lowest value. */
	MIN("min"),

	/** The last value. */
	LAST("last");

	private final String json;

	Reduction(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
