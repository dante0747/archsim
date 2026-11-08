package io.archsim.engine.components.loadbalancer;

import java.util.ArrayList;
import java.util.List;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.text.NumberText;
import io.archsim.model.component.loadbalancer.LoadBalancerConfig;
import io.archsim.model.text.PythonFormat;

/**
 * The calculations behind a load balancer's own behaviour: its upstream timeout, its rate
 * limit and its circuit breaker, each when it has one.
 */
final class LoadBalancerExplain {

	private static final double MS_PER_SECOND = 1000.0;

	private LoadBalancerExplain() {
	}

	/**
	 * A balancer's formulas, in the report's order.
	 * @param config its settings
	 * @return its upstream timeout's, rate limit's and circuit breaker's, each when it
	 * has one
	 */
	static List<Formula> of(LoadBalancerConfig config) {
		List<Formula> formulas = new ArrayList<>();
		config.effectiveUpstreamTimeoutMs().ifPresent((timeoutMs) -> formulas.add(upstreamTimeout(config, timeoutMs)));
		Double rate = config.rateLimitRps();
		if (rate != null) {
			Double burst = config.rateLimitBurst();
			// without a burst the formula shows the rate, as the Python engine's does,
			// though the bucket holds a second's tokens, at least one
			formulas.add(rateLimit(rate, (burst != null) ? burst : rate));
		}
		config.effectiveCircuitBreakerMaxRequests().ifPresent((breaker) -> formulas.add(circuitBreaker(breaker)));
		return formulas;
	}

	private static Formula upstreamTimeout(LoadBalancerConfig config, double upstreamTimeoutMs) {
		return Formula
			.of("upstream_timeout", "Upstream timeout",
					"effective timeout = min(link timeout, balancer's upstream timeout)",
					"upstream timeout " + NumberText.formula(upstreamTimeoutMs / MS_PER_SECOND, 1) + " s ("
							+ config.technology().json() + ")")
			.withResult(upstreamTimeoutMs, "ms")
			.withNote("A target slower than this gets a 504 from the balancer, while the target keeps working on "
					+ "the abandoned request.")
			.withMetric("upstreamTimeoutRps");
	}

	private static Formula rateLimit(double rate, double burst) {
		return Formula
			.of("rate_limit", "Rate limit (token bucket)", "admitted ≤ rate × time + burst",
					NumberText.formula(rate, 1) + " req/s, burst " + NumberText.formula(burst, 1))
			.withResult(rate, "req/s")
			.withNote("Requests that find the bucket empty get 429.")
			.withMetric("rateLimitedRps");
	}

	private static Formula circuitBreaker(int breaker) {
		return Formula
			.of("circuit_breaker", "Circuit breaker", "503 when requests in flight to a target ≥ max requests",
					"max " + PythonFormat.format(breaker, ",") + " per target")
			.withResult((double) breaker, "requests")
			.withNote("By Little's law a target reaches it when arrival rate × its response time ≥ the limit.");
	}

}
