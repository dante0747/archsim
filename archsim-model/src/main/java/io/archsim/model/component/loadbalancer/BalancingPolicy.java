package io.archsim.model.component.loadbalancer;

import io.archsim.model.codec.JsonEnum;

/** How a load balancer picks a target instance. */
public enum BalancingPolicy implements JsonEnum {

	/** The instances take turns. */
	ROUND_ROBIN("round_robin"),
	/** The instance with the fewest requests in flight. */
	LEAST_CONNECTIONS("least_connections"),
	/** An instance picked at random. */
	RANDOM("random");

	private final String json;

	BalancingPolicy(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
