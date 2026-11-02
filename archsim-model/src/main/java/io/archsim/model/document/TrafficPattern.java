package io.archsim.model.document;

import io.archsim.model.codec.Tagged;

/**
 * The shape of the offered load over time, told apart in JSON by its {@code kind}: {@code
 * constant}, {@code ramp}, {@code burst} or {@code trace}.
 */
@Tagged("kind")
public sealed interface TrafficPattern permits ConstantPattern, RampPattern, BurstPattern, TracePattern {}
