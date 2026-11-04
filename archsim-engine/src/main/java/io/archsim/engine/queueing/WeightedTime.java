package io.archsim.engine.queueing;

/**
 * A time with the weight it has in a mix, such as a request path and its requests per
 * second.
 *
 * @param weight how much of the mix it is
 * @param time its moments
 */
public record WeightedTime(double weight, Moments time) {
}
