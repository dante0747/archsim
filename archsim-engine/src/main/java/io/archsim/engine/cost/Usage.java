package io.archsim.engine.cost;

import org.jspecify.annotations.Nullable;

/**
 * What a part handles at the workload's steady base load.
 *
 * @param rps its requests per second
 * @param read its reads per second
 * @param write its writes per second
 * @param durationMs a function's time per request, in milliseconds; {@code null} when
 * unknown
 */
record Usage(double rps, double read, double write, @Nullable Double durationMs) {
}
