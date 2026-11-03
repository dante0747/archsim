package io.archsim.model.component.external;

/**
 * What each setting of an external API means, as the editor's inspector and the component
 * catalog show it.
 */
final class Descriptions {

	static final String COST_GROUP = "Cost";

	static final String LIMITS_GROUP = "Limits";

	static final String PERFORMANCE_GROUP = "Performance";

	static final String RELIABILITY_GROUP = "Reliability";

	static final String TECHNOLOGY_GROUP = "Technology";

	static final String TECHNOLOGY = "Kind of provider. A label only; presets set latency, error rate, limits and "
			+ "price to illustrative defaults.";

	static final String LATENCY_MS = "Time the provider takes to answer one call, excluding network to it (ms).";

	static final String ERROR_RATE = "Share of calls the provider fails (5xx / errors), after its response time.";

	static final String RATE_LIMIT_RPS = "Calls per second the provider accepts (bursts up to one second's worth); "
			+ "more are rejected as rate limited. Empty = no limit.";

	static final String CONCURRENCY_LIMIT = "Calls that may be in flight at once; more are rejected as throttled. "
			+ "Empty = no limit.";

	static final String COST_PER_MILLION_REQUESTS = "Price per million calls, used for cost estimates only. "
			+ "Illustrative default.";

	private Descriptions() {
	}

}
