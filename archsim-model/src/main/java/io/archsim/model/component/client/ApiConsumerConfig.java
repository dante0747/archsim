package io.archsim.model.component.client;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;

/**
 * Partners and other systems calling the public API, with a short timeout and SDK-style
 * retries.
 *
 * @param timeoutMs how long a client waits per attempt, in milliseconds
 * @param maxAttempts attempts per user request, including retries
 * @param retryBackoffMs the delay before the first retry, in milliseconds
 * @param trafficShare the group's share of the workload (percent)
 * @param technology what the consumers use
 */
public record ApiConsumerConfig(@Default("5000.0") @Gt(0) @Le(600_000) double timeoutMs,
		@Default("3") @Ge(1) @Le(5) int maxAttempts, @Default("200.0") @Ge(0) @Le(60_000) double retryBackoffMs,
		@Default("15.0") @Gt(0) @Le(100) double trafficShare,
		@Default("api_client") ApiClientTechnology technology) implements ClientConfig {
}
