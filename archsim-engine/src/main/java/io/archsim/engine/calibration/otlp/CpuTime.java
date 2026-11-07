package io.archsim.engine.calibration.otlp;

/**
 * The span attribute in which a service records a SERVER span's CPU time: OpenTelemetry
 * has no standard one, so it is whatever the instrumentation records.
 *
 * @param attribute the attribute's key
 * @param msPerUnit the milliseconds in one unit of its value
 */
public record CpuTime(String attribute, double msPerUnit) {

}
