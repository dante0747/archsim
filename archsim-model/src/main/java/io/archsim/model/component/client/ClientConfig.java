package io.archsim.model.component.client;

/** What every client group's config has: how the clients wait, retry, and how much load they send. */
public sealed interface ClientConfig permits TrafficSourceConfig, MobileAppConfig, WebClientConfig, ApiConsumerConfig {

    /** How long a client waits per attempt before giving up, in milliseconds. */
    double timeoutMs();

    /** Attempts per user request, including retries. */
    int maxAttempts();

    /** The delay before the first retry, in milliseconds, doubling with each further one. */
    double retryBackoffMs();

    /** The group's share of the workload, relative to the other groups' (percent). */
    double trafficShare();
}
