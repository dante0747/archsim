package io.archsim.model.component.client;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;

/**
 * Users in a web browser: they do not retry by default.
 *
 * @param timeoutMs how long a client waits per attempt, in milliseconds
 * @param maxAttempts attempts per user request, including retries
 * @param retryBackoffMs the delay before the first retry, in milliseconds
 * @param trafficShare the group's share of the workload (percent)
 * @param technology what the site is built with
 */
public record WebClientConfig(
        @Default("15000.0") @Gt(0) @Le(600_000) double timeoutMs,
        @Default("1") @Ge(1) @Le(5) int maxAttempts,
        @Default("100.0") @Ge(0) @Le(60_000) double retryBackoffMs,
        @Default("35.0") @Gt(0) @Le(100) double trafficShare,
        @Default("browser") WebTechnology technology)
        implements ClientConfig {}
