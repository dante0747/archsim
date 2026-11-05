package io.archsim.engine.report;

import org.jspecify.annotations.Nullable;

/**
 * One share of a traced request's time in a run's report: a part's, or the network's.
 *
 * @param nodeId the part whose time it is; {@code null} for the network
 * @param label the part's name, or {@value #NETWORK}
 * @param share its share of the request's time, from 0 to 1; {@code null} when the report
 * does not say
 */
public record LatencyShare(@Nullable String nodeId, @Nullable String label, @Nullable Double share) {

	/** The name of the network's share, the time a request spends on links. */
	public static final String NETWORK = "Network";

}
