package io.archsim.benchmarks.experiments;

import java.util.Map;
import java.util.Optional;

/**
 * The model's node for each measured container. The Kafka consumer and broker have none:
 * the model's queue node has no CPU or memory.
 */
public final class ContainerNodes {

	private static final Map<String, String> NODES = Map.ofEntries(Map.entry("api", "api"),
			Map.entry("producer", "api"), Map.entry("postgres", "db"), Map.entry("redis", "cache"),
			Map.entry("api-java", "api"), Map.entry("api-go", "api"), Map.entry("api-python", "api"),
			Map.entry("gateway", "gateway"), Map.entry("chain-api", "api"), Map.entry("api-ttl", "api"),
			Map.entry("redis-ttl", "cache"));

	private ContainerNodes() {
	}

	/**
	 * The model's node for a container.
	 * @param container the container's compose service
	 * @return its node's id; empty when the model has none
	 */
	public static Optional<String> of(String container) {
		return Optional.ofNullable(NODES.get(container));
	}

}
