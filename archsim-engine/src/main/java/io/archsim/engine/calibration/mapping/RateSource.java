package io.archsim.engine.calibration.mapping;

import io.archsim.model.codec.Default;

/**
 * The workload's arrival rate.
 *
 * @param rate the query of requests per second
 * @param trace whether the observed curve becomes the workload's {@code trace} pattern
 */
public record RateSource(String rate, @Default("true") boolean trace) {

}
