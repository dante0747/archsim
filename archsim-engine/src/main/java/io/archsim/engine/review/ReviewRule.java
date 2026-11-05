package io.archsim.engine.review;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.archsim.model.codec.JsonEnum;

/** The design review's rules, each with the title the web app lists it under. */
public enum ReviewRule implements JsonEnum {

	/** A part whose failure takes down the requests that pass through it. */
	SPOF("spof", "Single point of failure"),

	/** Reads that fail when their cache does. */
	CACHE_DEPENDENCY("cache-dependency", "Cache without fallback"),

	/** A call that waits as long as its callee takes. */
	MISSING_TIMEOUT("missing-timeout", "Missing timeout"),

	/** Retries multiplied along a path. */
	RETRY_AMPLIFICATION("retry-amplification", "Retry amplification"),

	/** Retries sent at once. */
	RETRY_NO_BACKOFF("retry-no-backoff", "Retries without backoff"),

	/** A caller that gives up before its callee does. */
	TIMEOUT_INVERSION("timeout-inversion", "Timeout inversion"),

	/** Requests that may wait in a queue forever. */
	UNBOUNDED_WAIT("unbounded-wait", "Unbounded wait"),

	/** More blocking callers than a data store has connections. */
	POOL_EXHAUSTION("pool-exhaustion", "Connection-pool exhaustion"),

	/** Serverless functions that may open more connections than a data store accepts. */
	CONNECTION_STORM("connection-storm", "Serverless connection storm"),

	/** An autoscaling range that cannot scale out, or scales late. */
	AUTOSCALING_RANGE("autoscaling-range", "Autoscaling range"),

	/** A queue whose backlog has no limit. */
	QUEUE_UNBOUNDED("queue-unbounded", "Unbounded backlog"),

	/** A queue whose consumers call nothing. */
	QUEUE_SINK("queue-sink", "Queue without consumers"),

	/** More consumers than partitions. */
	QUEUE_PARTITIONS("queue-partitions", "Idle consumers"),

	/** A hit ratio higher than caches get. */
	OPTIMISTIC_CACHE("optimistic-cache", "Optimistic hit ratio"),

	/** Popular keys that crowd onto a few shards. */
	HOT_PARTITION("hot-partition", "Hot partition risk"),

	/** Every replica waited for on every operation. */
	CONSISTENCY_ALL("consistency-all", "Consistency ALL"),

	/** A third party's errors passed straight to users. */
	EXTERNAL_ERRORS("external-errors", "Unhandled third-party errors"),

	/** No rate limit in front of the system. */
	EDGE_RATE_LIMIT("edge-rate-limit", "No rate limit at the edge"),

	/** Many synchronous hops on one request path. */
	DEEP_CHAIN("deep-chain", "Long synchronous chain"),

	/** A part whose CPU is nearly or more than full at the peak. */
	CPU_CAPACITY("cpu-capacity", "CPU capacity at peak"),

	/** A data store whose hot data does not fit in its memory. */
	BUFFER_CACHE("buffer-cache", "Working set larger than memory"),

	/** A cache whose memory holds fewer keys than the workload uses. */
	CACHE_CAPACITY("cache-capacity", "Cache smaller than its keyspace");

	private final String json;

	private final String title;

	ReviewRule(String json, String title) {
		this.json = json;
		this.title = title;
	}

	@Override
	public String json() {
		return this.json;
	}

	/**
	 * The title the web app lists the rule under.
	 * @return the title
	 */
	public String title() {
		return this.title;
	}

	/**
	 * Every rule's title by its name.
	 * @return the titles, in the rules' order
	 */
	public static Map<String, String> titles() {
		Map<String, String> titles = new LinkedHashMap<>();
		for (ReviewRule rule : values()) {
			titles.put(rule.json, rule.title);
		}
		return Collections.unmodifiableMap(titles);
	}

}
