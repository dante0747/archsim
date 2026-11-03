package io.archsim.model.component.client;

/**
 * What each setting of a client group means, as the editor's inspector and the component
 * catalog show it.
 */
final class Descriptions {

	static final String CLIENT_BEHAVIOUR_GROUP = "Client behaviour";

	static final String TECHNOLOGY_GROUP = "Technology";

	static final String TRAFFIC_GROUP = "Traffic";

	static final String TIMEOUT_MS = "How long a client waits for a response per attempt before giving up (ms).";

	static final String MAX_ATTEMPTS = "Total attempts per user request including retries (1 = no retries). Retries "
			+ "add load.";

	static final String RETRY_BACKOFF_MS = "Delay before the first retry (ms); doubles for each further retry.";

	static final String TRAFFIC_SHARE = "Share of the workload this client group sends (%). With several client "
			+ "groups the shares are relative: each sends its share divided by the sum of all shares, so 60 / 30 "
			+ "/ 10 means 60%, 30% and 10%. A single client group sends everything.";

	static final String TECHNOLOGY = "What the client is built with. A label and logo for the design; behaviour comes "
			+ "from the settings.";

	private Descriptions() {
	}

}
