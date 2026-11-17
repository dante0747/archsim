package io.archsim.model.component.external;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.DefaultFrom;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Ui;
import io.archsim.model.distribution.Distribution;
import io.archsim.model.distribution.LogNormal;

/**
 * A third-party service: how long it answers, how often it fails, and the limits it puts
 * on calls.
 *
 * @param technology the kind of provider (a label)
 * @param latencyMs the provider's response time, network excluded
 * @param errorRate the share of calls it fails
 * @param rateLimitRps calls per second it accepts; {@code null}: no limit
 * @param concurrencyLimit calls in flight it accepts; {@code null}: no limit
 * @param costPerMillionRequests the price per million calls, in USD; {@code null}: the
 * technology preset's price, else not priced (schema 1.9, ADR-0081)
 */
public record ExternalApiConfig(
		@Default("generic") @Description(Descriptions.TECHNOLOGY) @Ui(label = "Kind of service",
				group = Descriptions.TECHNOLOGY_GROUP) ExternalApiTechnology technology,

		@DefaultFrom("DEFAULT_LATENCY") @Description(Descriptions.LATENCY_MS) @Ui(label = "Response time", unit = "ms",
				group = Descriptions.PERFORMANCE_GROUP, assumption = true, headline = true) Distribution latencyMs,

		@Default("0.001") @Description(Descriptions.ERROR_RATE) @Ui(group = Descriptions.RELIABILITY_GROUP,
				assumption = true, headline = true, step = 0.001) @Ge(0) @Le(1) double errorRate,
		@Description(Descriptions.RATE_LIMIT_RPS) @Ui(label = "Rate limit", unit = "req/s",
				group = Descriptions.LIMITS_GROUP, assumption = true,
				headline = true) @Gt(0) @Le(1_000_000) @Nullable Double rateLimitRps,
		@Description(Descriptions.CONCURRENCY_LIMIT) @Ui(group = Descriptions.LIMITS_GROUP,
				assumption = true) @Ge(1) @Le(100_000) @Nullable Integer concurrencyLimit,
		@Description(Descriptions.COST_PER_MILLION_REQUESTS) @Ui(label = "Cost per million calls", unit = "$",
				group = Descriptions.COST_GROUP,
				assumption = true) @Ge(0) @Le(10_000_000) @Nullable Double costPerMillionRequests) {

	/** The provider's default response time: 120 ms median, 500 ms p95. */
	static final Distribution DEFAULT_LATENCY = new LogNormal(120.0, 500.0);

}
