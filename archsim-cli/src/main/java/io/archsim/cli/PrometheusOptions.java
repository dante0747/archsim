package io.archsim.cli;

import org.jspecify.annotations.Nullable;

/**
 * What {@code archsim calibrate prometheus} was asked, each option as the Python CLI used
 * it ({@link PythonOptions}).
 *
 * @param design the design's file
 * @param mapping the mapping's file
 * @param source where the responses come from
 * @param range the range asked for, {@code START/END}; {@code null} for the mapping's
 * @param step the seconds between points of the range asked for
 * @param replay whether the calibrated design is replayed and checked
 * @param recordTo where the responses are recorded; {@code null} for nowhere
 */
record PrometheusOptions(String design, String mapping, PrometheusSource source, @Nullable String range, double step,
		boolean replay, @Nullable String recordTo) {

}
