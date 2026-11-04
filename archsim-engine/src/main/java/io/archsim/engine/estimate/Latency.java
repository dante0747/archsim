package io.archsim.engine.estimate;

import org.jspecify.annotations.Nullable;

/**
 * The estimated latency of a request, over every request type and client.
 *
 * @param mean the mean, in milliseconds, to two decimals
 * @param p95 the rough p95, in milliseconds, to two decimals
 */
public record Latency(@Nullable Double mean, @Nullable Double p95) {
}
