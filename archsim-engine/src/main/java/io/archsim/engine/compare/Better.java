package io.archsim.engine.compare;

import io.archsim.model.codec.JsonEnum;

/** Which way a figure is better. */
public enum Better implements JsonEnum {

	/** A higher figure is better, such as throughput. */
	HIGHER("higher"),

	/** A lower figure is better, such as latency. */
	LOWER("lower"),

	/** Neither: the figure describes the question, such as the offered load. */
	NEUTRAL("neutral");

	private final String json;

	Better(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
