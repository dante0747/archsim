package io.archsim.engine.check;

import io.archsim.model.codec.JsonEnum;

/** A figure a check compares with the baseline's. */
public enum Metric implements JsonEnum {

	/** The p95 latency. */
	P95("p95", "Latency p95"),

	/** The share of requests that succeed. */
	SUCCESS_RATE("successRate", "Success rate"),

	/** The monthly cost. */
	COST_MONTHLY("costMonthly", "Monthly cost"),

	/** The highest load within the objectives. */
	KNEE("knee", "Breaking point");

	private final String json;

	private final String label;

	Metric(String json, String label) {
		this.json = json;
		this.label = label;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * What a check calls the figure.
	 * @return such as {@code Latency p95}
	 */
	public String label() {
		return this.label;
	}

}
