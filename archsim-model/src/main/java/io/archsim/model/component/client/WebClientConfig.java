package io.archsim.model.component.client;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Ui;

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
		@Default("15000.0") @Description(Descriptions.TIMEOUT_MS) @Ui(label = "Client timeout", unit = "ms",
				group = Descriptions.CLIENT_BEHAVIOUR_GROUP, headline = true) @Gt(0) @Le(600_000) double timeoutMs,
		@Default("1") @Description(Descriptions.MAX_ATTEMPTS) @Ui(
				group = Descriptions.CLIENT_BEHAVIOUR_GROUP) @Ge(1) @Le(5) int maxAttempts,
		@Default("100.0") @Description(Descriptions.RETRY_BACKOFF_MS) @Ui(label = "Retry backoff", unit = "ms",
				group = Descriptions.CLIENT_BEHAVIOUR_GROUP) @Ge(0) @Le(60_000) double retryBackoffMs,
		@Default("35.0") @Description(Descriptions.TRAFFIC_SHARE) @Ui(unit = "%",
				group = Descriptions.TRAFFIC_GROUP) @Gt(0) @Le(100) double trafficShare,
		@Default("browser") @Description(Descriptions.TECHNOLOGY) @Ui(
				group = Descriptions.TECHNOLOGY_GROUP) WebTechnology technology)
		implements
			ClientConfig {
}
