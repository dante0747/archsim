package io.archsim.model.component.loadbalancer;

import io.archsim.model.codec.JsonEnum;

/** How a load balancer picks a target instance. */
public enum BalancingPolicy implements JsonEnum {

	ROUND_ROBIN("round_robin"), LEAST_CONNECTIONS("least_connections"), RANDOM("random");

	private final String json;

	BalancingPolicy(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return json;
	}

}
