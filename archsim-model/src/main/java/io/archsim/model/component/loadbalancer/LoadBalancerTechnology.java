package io.archsim.model.component.loadbalancer;

import io.archsim.model.codec.JsonEnum;

/** The product or managed service balancing the load. */
public enum LoadBalancerTechnology implements JsonEnum {

	/** A load balancer of no particular product. */
	GENERIC("generic"),
	/** NGINX, a web server and reverse proxy. */
	NGINX("nginx"),
	/** HAProxy, a TCP and HTTP load balancer. */
	HAPROXY("haproxy"),
	/** Envoy, a service proxy with circuit breakers. */
	ENVOY("envoy"),
	/** Traefik, a reverse proxy for containers. */
	TRAEFIK("traefik"),
	/** Kong, an API gateway built on NGINX. */
	KONG("kong"),
	/** An AWS Application Load Balancer. */
	AWS_ALB("aws_alb"),
	/** A managed API gateway, such as Amazon API Gateway. */
	API_GATEWAY("api_gateway");

	private final String json;

	LoadBalancerTechnology(String json) {
		this.json = json;
	}

	@Override
	public String json() {
		return this.json;
	}

}
