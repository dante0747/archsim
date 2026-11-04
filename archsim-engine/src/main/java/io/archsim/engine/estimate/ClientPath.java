package io.archsim.engine.estimate;

import org.jspecify.annotations.Nullable;

/**
 * One client's requests of one kind, and their mean latency.
 *
 * @param clientId the client's id
 * @param client its label
 * @param op reads or writes
 * @param rps the requests per second, to two decimals
 * @param meanMs their mean latency, in milliseconds
 */
public record ClientPath(String clientId, String client, Op op, @Nullable Double rps, @Nullable Double meanMs) {
}
