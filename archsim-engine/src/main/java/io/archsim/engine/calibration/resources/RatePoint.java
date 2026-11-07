package io.archsim.engine.calibration.resources;

/**
 * One measurement against load: a rate and what was measured at it.
 *
 * @param rate the rate, in requests per second
 * @param value what was measured: CPU cores in use, or CPU per request in milliseconds
 */
public record RatePoint(double rate, double value) {

}
