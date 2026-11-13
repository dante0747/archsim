package io.archsim.benchmarks.range;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A kept run the range is fitted and checked on: its predicted and measured knees, the
 * knee's sensitivity to each varied input, and its log error.
 *
 * @param experiment the run's experiment
 * @param source its folder under {@code results/}
 * @param calibratedAt when it was calibrated
 * @param measuredKnee the knee it measured
 * @param knee the knee today's model predicts from its calibration
 * @param sensitivity the knee's sensitivity to each varied input ({@link Sensitivity})
 * @param error ln(measured / predicted knee)
 * @param before the range it was predicted with, or None
 */
record CountedRun(String experiment, String source, JsonNode calibratedAt, JsonNode measuredKnee, JsonNode knee,
		ObjectNode sensitivity, double error, JsonNode before) {

}
