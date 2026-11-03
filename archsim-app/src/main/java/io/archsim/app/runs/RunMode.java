package io.archsim.app.runs;

import io.archsim.model.codec.JsonEnum;

/**
 * How a run is simulated.
 */
enum RunMode implements JsonEnum {

	/** The whole run, request by request. */
	FULL("full"),

	/**
	 * A quick check: answered from a steady sample when the design qualifies (constant
	 * traffic, no faults, a long enough run).
	 */
	SAMPLED("sampled");

	private final String json;

	RunMode(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * The mode a run stored.
	 * @param json {@code sampled}, or anything else for a full run
	 * @return the mode
	 */
	static RunMode of(String json) {
		return SAMPLED.json.equals(json) ? SAMPLED : FULL;
	}

}
