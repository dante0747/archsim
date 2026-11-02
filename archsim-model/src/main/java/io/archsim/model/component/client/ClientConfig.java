package io.archsim.model.component.client;

/**
 * What every client group's config has: how the clients wait, retry, and how much load
 * they send.
 */
public sealed interface ClientConfig permits TrafficSourceConfig, MobileAppConfig, WebClientConfig, ApiConsumerConfig {

	/**
	 * How long a client waits per attempt before giving up.
	 * @return the timeout, in milliseconds
	 */
	double timeoutMs();

	/**
	 * Attempts per user request, including retries.
	 * @return the most attempts, at least one
	 */
	int maxAttempts();

	/**
	 * The delay before the first retry, doubling with each further one.
	 * @return the backoff, in milliseconds
	 */
	double retryBackoffMs();

	/**
	 * The group's share of the workload, relative to the other groups'.
	 * @return the share, in percent
	 */
	double trafficShare();

}
