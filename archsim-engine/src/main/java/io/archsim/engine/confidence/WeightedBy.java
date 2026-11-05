package io.archsim.engine.confidence;

import io.archsim.model.codec.JsonEnum;

/** What each part's weight in a result is. */
public enum WeightedBy implements JsonEnum {

	/** Its share of a request's latency, in a run's report. */
	LATENCY("latency", "of the latency"),

	/** Its utilization at the peak. */
	UTILIZATION("utilization", "of the load (by part utilization)"),

	/** One for every part with inputs, when no part has a weight. */
	INPUTS("inputs", "of the parts' inputs");

	private final String json;

	private final String ofWhat;

	WeightedBy(String json, String ofWhat) {
		this.json = json;
		this.ofWhat = ofWhat;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * What the grounded share is a share of, in words.
	 * @return such as {@code of the latency}
	 */
	String ofWhat() {
		return this.ofWhat;
	}

}
