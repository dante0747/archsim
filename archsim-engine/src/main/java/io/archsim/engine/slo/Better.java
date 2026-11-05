package io.archsim.engine.slo;

import io.archsim.model.codec.JsonEnum;

/** Which way a figure is better: a latency lower, availability and throughput higher. */
public enum Better implements JsonEnum {

	/** At most the target. */
	LOWER("lower", "<="),

	/** At least the target. */
	HIGHER("higher", ">=");

	private final String json;

	private final String sign;

	Better(String json, String sign) {
		this.json = json;
		this.sign = sign;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * Whether a figure meets its target.
	 * @param actual the figure
	 * @param target the target
	 * @return whether it is at most (or at least) the target
	 */
	boolean meets(double actual, double target) {
		return (this == LOWER) ? actual <= target : actual >= target;
	}

	/**
	 * How a verdict writes the target's side: {@code <=} or {@code >=}.
	 * @return the sign
	 */
	String sign() {
		return this.sign;
	}

}
