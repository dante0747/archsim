package io.archsim.model.component.loadbalancer;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.DefaultFrom;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Matches;
import io.archsim.model.codec.Ui;
import io.archsim.model.component.Resources;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.component.TechnologyProfiles;
import io.archsim.model.distribution.Constant;
import io.archsim.model.distribution.Distribution;

/**
 * A load balancer: how it picks targets, what it adds per request, and its limits.
 *
 * @param technology the product or managed service
 * @param policy how a target instance is picked
 * @param overheadMs the time the balancer adds per request
 * @param maxConnections requests it holds at once; more are refused
 * @param rateLimitRps requests per second it admits; {@code null}: no limit
 * @param rateLimitBurst requests admitted in a burst above the rate; {@code null}: a
 * second's worth
 * @param upstreamTimeoutMs how long it waits for a target; {@code null}: the technology's
 * default
 * @param circuitBreakerMaxRequests requests in flight to one target before it refuses
 * more; {@code null}: the technology's default
 * @param cpuCores vCPU per instance (for the design and prices only)
 * @param memoryGib memory per instance (for the design and prices only)
 * @param instanceType the instance type in the design's price snapshot
 * @param costPerHour the price per hour, in USD
 */
public record LoadBalancerConfig(
		@Default("generic") @Description(Descriptions.TECHNOLOGY) @Ui(
				group = Descriptions.TECHNOLOGY_GROUP) LoadBalancerTechnology technology,
		@Default("round_robin") @Description(Descriptions.POLICY) @Ui(label = "Balancing policy",
				group = Descriptions.ROUTING_GROUP, headline = true) BalancingPolicy policy,
		@DefaultFrom("DEFAULT_OVERHEAD") @Description(Descriptions.OVERHEAD_MS) @Ui(label = "Overhead", unit = "ms",
				group = Descriptions.PERFORMANCE_GROUP, assumption = true) Distribution overheadMs,
		@Default("10000") @Description(Descriptions.MAX_CONNECTIONS) @Ui(
				group = Descriptions.CAPACITY_GROUP) @Ge(1) @Le(1_000_000) int maxConnections,
		@Description(Descriptions.RATE_LIMIT_RPS) @Ui(label = "Rate limit", unit = "req/s",
				group = Descriptions.RATE_LIMITING_GROUP) @Gt(0) @Le(1_000_000) @Nullable Double rateLimitRps,
		@Description(Descriptions.RATE_LIMIT_BURST) @Ui(label = "Burst", unit = "req",
				group = Descriptions.RATE_LIMITING_GROUP) @Gt(0) @Le(10_000_000) @Nullable Double rateLimitBurst,
		@Description(Descriptions.UPSTREAM_TIMEOUT_MS) @Ui(label = "Upstream timeout", unit = "ms",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) @Gt(0) @Le(3_600_000) @Nullable Double upstreamTimeoutMs,
		@Description(Descriptions.CIRCUIT_BREAKER_MAX_REQUESTS) @Ui(label = "Circuit breaker: max requests per target",
				group = Descriptions.TECHNOLOGY_BEHAVIOUR_GROUP) @Ge(1) @Le(1_000_000) @Nullable Integer circuitBreakerMaxRequests,
		@Description(Descriptions.CPU_CORES) @Ui(label = "CPU", unit = "vCPU", group = Descriptions.RESOURCES_GROUP,
				step = 0.5) @Gt(0) @Le(Resources.MAX_CPU_CORES) @Nullable Double cpuCores,
		@Description(Descriptions.MEMORY_GIB) @Ui(label = "Memory", unit = "GiB", group = Descriptions.RESOURCES_GROUP,
				step = 0.5) @Gt(0) @Le(Resources.MAX_MEMORY_GIB) @Nullable Double memoryGib,
		@Description(Descriptions.INSTANCE_TYPE) @Ui(
				group = Descriptions.RESOURCES_GROUP) @Matches(Resources.INSTANCE_TYPE) @Nullable String instanceType,
		@Default("0.025") @Description(Descriptions.COST_PER_HOUR) @Ui(unit = "$/h", group = Descriptions.COST_GROUP,
				assumption = true) @Ge(0) @Le(10_000) double costPerHour) {

	/** The time the balancer adds by default: 0.2 ms, every time. */
	static final Distribution DEFAULT_OVERHEAD = new Constant(0.2);

	/** What each technology does by default (technology.py's {@code LB_PROFILES}). */
	static final TechnologyProfiles<LoadBalancerTechnology> PROFILES = TechnologyProfiles
		.builder(LoadBalancerTechnology.class)
		.with(LoadBalancerTechnology.NGINX, TechnologyProfile.core().with("upstreamTimeoutMs", 60_000.0))
		.with(LoadBalancerTechnology.ENVOY,
				TechnologyProfile.experimental()
					.with("upstreamTimeoutMs", 15_000.0)
					.with("circuitBreakerMaxRequests", 1024))
		.with(LoadBalancerTechnology.AWS_ALB, TechnologyProfile.core().with("upstreamTimeoutMs", 60_000.0))
		.with(LoadBalancerTechnology.API_GATEWAY, TechnologyProfile.experimental().with("upstreamTimeoutMs", 29_000.0))
		.with(LoadBalancerTechnology.KONG, TechnologyProfile.experimental().with("upstreamTimeoutMs", 60_000.0))
		.with(LoadBalancerTechnology.HAPROXY, TechnologyProfile.experimental().withoutDefault("upstreamTimeoutMs"))
		.with(LoadBalancerTechnology.TRAEFIK, TechnologyProfile.experimental().withoutDefault("upstreamTimeoutMs"))
		.with(LoadBalancerTechnology.GENERIC, TechnologyProfile.core().withoutDefault("upstreamTimeoutMs"))
		.build();

	public TechnologyProfile profile() {
		return PROFILES.of(this.technology);
	}

	/**
	 * How long the balancer waits for a target before answering 504 itself, if it gives
	 * up at all.
	 * @return the upstream timeout in milliseconds, set or the technology's default
	 */
	public Optional<Double> effectiveUpstreamTimeoutMs() {
		return profile().number("upstreamTimeoutMs", this.upstreamTimeoutMs);
	}

	/**
	 * Requests in flight to one target before new ones are refused, if there is a limit.
	 * @return the circuit breaker's limit, set or the technology's default
	 */
	public Optional<Integer> effectiveCircuitBreakerMaxRequests() {
		return profile()
			.number("circuitBreakerMaxRequests",
					(this.circuitBreakerMaxRequests == null) ? null : (double) this.circuitBreakerMaxRequests)
			.map(Double::intValue);
	}

}
