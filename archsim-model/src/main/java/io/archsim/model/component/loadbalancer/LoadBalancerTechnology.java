package io.archsim.model.component.loadbalancer;

import io.archsim.model.codec.JsonEnum;

/** The product or managed service balancing the load. */
public enum LoadBalancerTechnology implements JsonEnum {
    GENERIC("generic"),
    NGINX("nginx"),
    HAPROXY("haproxy"),
    ENVOY("envoy"),
    TRAEFIK("traefik"),
    KONG("kong"),
    AWS_ALB("aws_alb"),
    API_GATEWAY("api_gateway");

    private final String json;

    LoadBalancerTechnology(String json) {
        this.json = json;
    }

    @Override
    public String json() {
        return json;
    }
}
