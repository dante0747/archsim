package io.archsim.engine.report;

import org.jspecify.annotations.Nullable;

/**
 * A latency's statistics, in milliseconds; {@code null} when no request was measured.
 *
 * @param mean the mean
 * @param p50 the median
 * @param p95 the 95th percentile
 * @param p99 the 99th percentile
 */
public record LatencyStats(@Nullable Double mean, @Nullable Double p50, @Nullable Double p95, @Nullable Double p99) {

}
