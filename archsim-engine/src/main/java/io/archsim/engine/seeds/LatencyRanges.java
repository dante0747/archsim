package io.archsim.engine.seeds;

import org.jspecify.annotations.Nullable;

/**
 * Latency percentiles' ranges across a group of runs; {@code null} where no run has the
 * percentile.
 *
 * @param p50 the median's range
 * @param p95 the p95's range
 * @param p99 the p99's range
 */
public record LatencyRanges(@Nullable ValueRange p50, @Nullable ValueRange p95, @Nullable ValueRange p99) {

}
