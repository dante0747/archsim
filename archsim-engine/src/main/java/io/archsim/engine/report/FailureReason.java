package io.archsim.engine.report;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.JsonEnum;

/**
 * Why a request was refused or failed, as a run's report names it in each part's
 * rejections and in its failure breakdown, with the words the report gives it there
 * ({@code REASON_TEXT}).
 */
public enum FailureReason implements JsonEnum {

	/** The part's queue was full. */
	QUEUE_FULL("queue_full", "queue was full"),

	/** The request waited in the queue longer than its timeout. */
	QUEUE_TIMEOUT("queue_timeout", "waited longer than the queue timeout"),

	/** The part had as many connections as it allows. */
	MAX_CONNECTIONS("max_connections", "connection limit reached"),

	/** The part gave up waiting for a part it calls. */
	DEPENDENCY_TIMEOUT("dependency_timeout", "timed out waiting for a dependency"),

	/** The client gave up waiting. */
	CLIENT_TIMEOUT("client_timeout", "client timeout"),

	/** A cache miss had no link to send it on. */
	MISS_UNROUTED("miss_unrouted", "miss had nowhere to go"),

	/** The part was down in an injected outage. */
	OUTAGE("outage", "component was down (injected outage)"),

	/** The part refused it over its concurrency or provisioned throughput. */
	THROTTLED("throttled", "throttled (concurrency or provisioned throughput exceeded)"),

	/** The part refused it over its rate limit. */
	RATE_LIMITED("rate_limited", "rate limited"),

	/** A third-party API answered with an error. */
	UPSTREAM_ERROR("upstream_error", "third-party API returned an error"),

	/** The part had no memory left for it. */
	OUT_OF_MEMORY("out_of_memory", "ran out of memory (request refused)"),

	/** A gateway gave up waiting for its target. */
	GATEWAY_TIMEOUT("gateway_timeout", "the gateway gave up waiting for its target (504)"),

	/** A circuit breaker refused it, with too many requests in flight to its target. */
	CIRCUIT_OPEN("circuit_open", "circuit breaker open: too many requests in flight to the target (503)"),

	/** No pod was ready to serve it. */
	NO_READY_PODS("no_ready_pods", "no ready pod to serve it: its pods were Pending or starting (503)");

	private final String json;

	private final String text;

	FailureReason(String json, String text) {
		this.json = json;
		this.text = text;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * Whether a reason, as a report names it, is this one.
	 * @param reason such as {@code queue_full}; {@code null} for none
	 * @return whether it is
	 */
	public boolean is(@Nullable String reason) {
		return this.json.equals(reason);
	}

	/**
	 * The words a report gives this reason.
	 * @return such as {@code queue was full}
	 */
	public String text() {
		return this.text;
	}

}
