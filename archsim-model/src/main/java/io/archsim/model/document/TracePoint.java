package io.archsim.model.document;

import io.archsim.model.codec.AsArray;

/**
 * One point of a {@link TracePattern}, written {@code [t, rps]}.
 *
 * @param t seconds from the start of the run
 * @param rps the offered rate there, in requests per second
 */
@AsArray
public record TracePoint(double t, double rps) {}
