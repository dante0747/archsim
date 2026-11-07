package io.archsim.engine.calibration.mapping;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;

/**
 * What a replay compares the calibrated model with: an observed latency histogram, and
 * the observed throughput.
 *
 * @param histogram the latency histogram query
 * @param unit the unit of its bounds
 * @param throughput the throughput query; {@code null} for none
 */
public record ReplayTarget(String histogram, @Default("s") LatencyUnit unit, @Nullable String throughput) {

}
