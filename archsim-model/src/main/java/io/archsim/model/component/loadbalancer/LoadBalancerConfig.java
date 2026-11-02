package io.archsim.model.component.loadbalancer;

import static io.archsim.model.component.Resources.INSTANCE_TYPE;
import static io.archsim.model.component.Resources.MAX_CPU_CORES;
import static io.archsim.model.component.Resources.MAX_MEMORY_GIB;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Matches;
import io.archsim.model.component.TechnologyProfile;
import io.archsim.model.component.TechnologyProfiles;
import io.archsim.model.distribution.Distribution;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
 * more; {@code
 *     null}: the technology's default
 * @param cpuCores vCPU per instance (for the design and prices only)
 * @param memoryGib memory per instance (for the design and prices only)
 * @param instanceType the instance type in the design's price snapshot
 * @param costPerHour the price per hour, in USD
 */
public record LoadBalancerConfig(@Default("generic") LoadBalancerTechnology technology,
		@Default("round_robin") BalancingPolicy policy,
		@Default("{\"kind\": \"constant\", \"value\": 0.2}") Distribution overheadMs,
		@Default("10000") @Ge(1) @Le(1_000_000) int maxConnections, @Gt(0) @Le(1_000_000) @Nullable Double rateLimitRps,
		@Gt(0) @Le(10_000_000) @Nullable Double rateLimitBurst,
		@Gt(0) @Le(3_600_000) @Nullable Double upstreamTimeoutMs,
		@Ge(1) @Le(1_000_000) @Nullable Integer circuitBreakerMaxRequests,
		@Gt(0) @Le(MAX_CPU_CORES) @Nullable Double cpuCores, @Gt(0) @Le(MAX_MEMORY_GIB) @Nullable Double memoryGib,
		@Matches(INSTANCE_TYPE) @Nullable String instanceType,
		@Default("0.025") @Ge(0) @Le(10_000) double costPerHour) {

	/** What each technology does by default (technology.py's {@code LB_PROFILES}). */
	static final TechnologyProfiles<LoadBalancerTechnology> PROFILES = new TechnologyProfiles<>(
			LoadBalancerTechnology.class,
			Map.of(LoadBalancerTechnology.NGINX, TechnologyProfile.core().with("upstreamTimeoutMs", 60_000.0),
					LoadBalancerTechnology.ENVOY,
					TechnologyProfile.experimental()
						.with("upstreamTimeoutMs", 15_000.0)
						.with("circuitBreakerMaxRequests", 1024),
					LoadBalancerTechnology.AWS_ALB, TechnologyProfile.core().with("upstreamTimeoutMs", 60_000.0),
					LoadBalancerTechnology.API_GATEWAY,
					TechnologyProfile.experimental().with("upstreamTimeoutMs", 29_000.0), LoadBalancerTechnology.KONG,
					TechnologyProfile.experimental().with("upstreamTimeoutMs", 60_000.0),
					LoadBalancerTechnology.HAPROXY, TechnologyProfile.experimental(), LoadBalancerTechnology.TRAEFIK,
					TechnologyProfile.experimental(), LoadBalancerTechnology.GENERIC, TechnologyProfile.core()));

	public TechnologyProfile profile() {
		return PROFILES.of(technology);
	}

	/**
	 * How long the balancer waits for a target before answering 504 itself, if it gives
	 * up at all.
	 */
	public Optional<Double> effectiveUpstreamTimeoutMs() {
		return profile().number("upstreamTimeoutMs", upstreamTimeoutMs);
	}

	/**
	 * Requests in flight to one target before new ones are refused, if there is a limit.
	 */
	public Optional<Integer> effectiveCircuitBreakerMaxRequests() {
		return profile()
			.number("circuitBreakerMaxRequests",
					circuitBreakerMaxRequests == null ? null : (double) circuitBreakerMaxRequests)
			.map(Double::intValue);
	}
}
