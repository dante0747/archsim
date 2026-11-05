package io.archsim.engine.findings;

import io.archsim.model.codec.JsonEnum;

/** What a run's finding is about. */
public enum FindingKind implements JsonEnum {

	/** The workload offered no request. */
	NO_TRAFFIC("no_traffic"),

	/** A saturated part that is a root cause. */
	BOTTLENECK("bottleneck"),

	/** A part running hot. */
	HOT("hot"),

	/** A saturated part held up by a saturated part downstream. */
	SYMPTOM("symptom"),

	/** Fewer requests completed than were offered. */
	THROUGHPUT_GAP("throughput_gap"),

	/** Where most failed requests failed. */
	FAILURES("failures"),

	/** Clients' retries added load. */
	RETRIES("retries"),

	/** Work finished for clients that had already given up. */
	WASTED_WORK("wasted_work"),

	/** A cache's hit ratio and where its misses went. */
	CACHE("cache"),

	/** No part is saturated. */
	HEALTHY("healthy"),

	/** One shard of a sharded data store gets far more than its fair share. */
	HOT_SHARD("hot_shard"),

	/** Throughput, rate or concurrency limits rejected requests. */
	THROTTLING("throttling"),

	/** A third party failed calls. */
	EXTERNAL_ERRORS("external_errors"),

	/** A third party accounts for most of a request's latency. */
	EXTERNAL_LATENCY("external_latency"),

	/** How much of the read load replicas absorbed. */
	READ_REPLICAS("read_replicas"),

	/** Replicas fell behind the primary. */
	REPLICATION_LAG("replication_lag"),

	/** Writes queued for row locks on hot keys. */
	LOCK_CONTENTION("lock_contention"),

	/** A cluster's pods waited for a node with room. */
	PODS_PENDING("pods_pending"),

	/** What a fault did. */
	FAULT_IMPACT("fault_impact"),

	/** How a service scaled. */
	AUTOSCALING("autoscaling"),

	/** A serverless function's cold starts. */
	SERVERLESS("serverless"),

	/** How far a queue's consumers fell behind. */
	QUEUE_LAG("queue_lag");

	private final String json;

	FindingKind(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
