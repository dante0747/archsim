package io.archsim.engine.estimate;

import org.jspecify.annotations.Nullable;

/**
 * Where an average request's time goes: one part's wait and own time, or the network.
 *
 * @param nodeId the part's id; {@code null} for the network
 * @param label the part's label, or {@code Network}
 * @param meanMs its time per request, in milliseconds
 * @param share its share of the request's time
 */
public record TimeShare(@Nullable String nodeId, String label, @Nullable Double meanMs, @Nullable Double share) {
}
