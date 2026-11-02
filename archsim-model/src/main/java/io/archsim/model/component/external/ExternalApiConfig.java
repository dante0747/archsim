package io.archsim.model.component.external;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.distribution.Distribution;
import org.jspecify.annotations.Nullable;

/**
 * A third-party service: how long it answers, how often it fails, and the limits it puts on calls.
 *
 * @param technology the kind of provider (a label)
 * @param latencyMs the provider's response time, network excluded
 * @param errorRate the share of calls it fails
 * @param rateLimitRps calls per second it accepts; {@code null}: no limit
 * @param concurrencyLimit calls in flight it accepts; {@code null}: no limit
 * @param costPerMillionRequests the price per million calls, in USD
 */
public record ExternalApiConfig(
        @Default("generic") ExternalApiTechnology technology,

        @Default("{\"kind\": \"lognormal\", \"median\": 120.0, \"p95\": 500.0}")
        Distribution latencyMs,

        @Default("0.001") @Ge(0) @Le(1) double errorRate,
        @Gt(0) @Le(1_000_000) @Nullable Double rateLimitRps,
        @Ge(1) @Le(100_000) @Nullable Integer concurrencyLimit,
        @Default("0.0") @Ge(0) @Le(10_000_000) double costPerMillionRequests) {}
