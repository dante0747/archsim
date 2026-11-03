package io.archsim.model.component.loadbalancer;

import io.archsim.model.component.Resources;

/**
 * What each setting of a load balancer means, as the editor's inspector and the component
 * catalog show it.
 */
final class Descriptions {

	static final String CAPACITY_GROUP = "Capacity";

	static final String COST_GROUP = "Cost";

	static final String PERFORMANCE_GROUP = "Performance";

	static final String RATE_LIMITING_GROUP = "Rate limiting";

	static final String RESOURCES_GROUP = "Resources";

	static final String ROUTING_GROUP = "Routing";

	static final String TECHNOLOGY_BEHAVIOUR_GROUP = "Technology behaviour";

	static final String TECHNOLOGY_GROUP = "Technology";

	static final String TECHNOLOGY = "Product or managed service. It sets the upstream timeout and circuit breakers "
			+ "shown under Technology behaviour; presets also fill in typical numbers.";

	static final String POLICY = "How a target instance is chosen. Instances are the replicas of every linked target "
			+ "that carries the request type.";

	static final String OVERHEAD_MS = "Processing overhead added per request by the balancer itself (ms).";

	static final String MAX_CONNECTIONS = "Concurrent requests the balancer accepts; beyond this new requests are "
			+ "rejected.";

	static final String RATE_LIMIT_RPS = "Requests per second admitted (token bucket); excess requests are rejected "
			+ "(HTTP 429). Empty = no limit.";

	static final String RATE_LIMIT_BURST = "Bucket size: requests admitted in a burst above the rate. Empty = one "
			+ "second's worth.";

	static final String UPSTREAM_TIMEOUT_MS = "How long the balancer waits for a target's response before answering "
			+ "504 itself, unless the link has a shorter timeout. Empty = the technology's default (nginx 60 s, "
			+ "Envoy 15 s, API Gateway 29 s, ALB 60 s; none for HAProxy and generic).";

	static final String CIRCUIT_BREAKER_MAX_REQUESTS = "Requests in flight to one target (upstream cluster) before "
			+ "new ones get 503 immediately (Envoy circuit breakers, 1,024 by default). Empty = technology "
			+ "default.";

	static final String CPU_CORES = Resources.CPU_PER + "instance" + Resources.NOT_MODELLED_WHEN_EMPTY
			+ Resources.PRICED_ONLY;

	static final String MEMORY_GIB = Resources.MEMORY_PER + "instance" + Resources.IN_GIB
			+ Resources.NOT_MODELLED_WHEN_EMPTY + Resources.PRICED_ONLY;

	static final String INSTANCE_TYPE = Resources.INSTANCE_TYPE_OF_EACH + "instance" + Resources.INSTANCE_TYPE_USE;

	static final String COST_PER_HOUR = "Price of the balancer per hour, in USD. Illustrative default; use your "
			+ "provider's pricing.";

	private Descriptions() {
	}

}
