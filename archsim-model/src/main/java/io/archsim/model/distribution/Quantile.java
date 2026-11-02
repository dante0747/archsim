package io.archsim.model.distribution;

import io.archsim.model.codec.AsArray;

/**
 * One point of an {@link Empirical} distribution, written {@code [p, ms]}.
 *
 * @param p the probability, from 0 to 1
 * @param ms the value at that probability, in milliseconds
 */
@AsArray
public record Quantile(double p, double ms) {}
