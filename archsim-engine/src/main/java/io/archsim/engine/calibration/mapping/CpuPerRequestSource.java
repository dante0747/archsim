package io.archsim.engine.calibration.mapping;

import io.archsim.model.codec.KeyTag;

/**
 * CPU per request, from the CPU in use and the rate of requests: it sets
 * {@code cpuShare}.
 *
 * @param cpu the CPU cores in use: the rate of CPU seconds, summed over replicas
 * @param requests the requests per second the part serves (queries, for a database)
 */
@KeyTag("cpu")
public record CpuPerRequestSource(String cpu, String requests) implements NodeSource {

}
