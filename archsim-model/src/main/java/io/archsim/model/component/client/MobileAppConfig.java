package io.archsim.model.component.client;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Ge;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.Ui;

/**
 * Users on phones: they wait longer and retry once by default.
 *
 * @param timeoutMs how long a client waits per attempt, in milliseconds
 * @param maxAttempts attempts per user request, including retries
 * @param retryBackoffMs the delay before the first retry, in milliseconds
 * @param trafficShare the group's share of the workload (percent)
 * @param technology what the app is built with
 */
public record MobileAppConfig(
		@Default("10000.0") @Description(Descriptions.TIMEOUT_MS) @Ui(label = "Client timeout", unit = "ms",
				group = Descriptions.CLIENT_BEHAVIOUR_GROUP, headline = true) @Gt(0) @Le(600_000) double timeoutMs,
		@Default("2") @Description(Descriptions.MAX_ATTEMPTS) @Ui(
				group = Descriptions.CLIENT_BEHAVIOUR_GROUP) @Ge(1) @Le(5) int maxAttempts,
		@Default("500.0") @Description(Descriptions.RETRY_BACKOFF_MS) @Ui(label = "Retry backoff", unit = "ms",
				group = Descriptions.CLIENT_BEHAVIOUR_GROUP) @Ge(0) @Le(60_000) double retryBackoffMs,
		@Default("50.0") @Description(Descriptions.TRAFFIC_SHARE) @Ui(unit = "%",
				group = Descriptions.TRAFFIC_GROUP) @Gt(0) @Le(100) double trafficShare,
		@Default("mobile") @Description(Descriptions.TECHNOLOGY) @Ui(
				group = Descriptions.TECHNOLOGY_GROUP) MobileTechnology technology)
		implements
			ClientConfig {
}
