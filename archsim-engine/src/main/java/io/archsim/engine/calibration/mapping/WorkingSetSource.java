package io.archsim.engine.calibration.mapping;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.KeyTag;

/**
 * The working set, fitted against the request rate, the requests held or both: it sets
 * {@code memoryBaselineMb}, {@code memoryPerRpsMb} and {@code memoryPerRequestMb}.
 *
 * @param workingSet the memory in use, summed over replicas
 * @param unit its unit
 * @param requests the requests per second, summed; {@code null} for none
 * @param inflight the requests held, summed over replicas; {@code null} for none
 * @param replicas the replicas over time; {@code null} for the design's replica count
 */
@KeyTag("workingSet")
public record WorkingSetSource(String workingSet, @Default("bytes") MemoryUnit unit, @Nullable String requests,
		@Nullable String inflight, @Nullable String replicas) implements NodeSource {

	/**
	 * Checks that the working set has something to be fitted against.
	 * @throws IllegalArgumentException with the Python engine's words otherwise
	 */
	public WorkingSetSource {
		if (requests == null && inflight == null) {
			throw new IllegalArgumentException("memoryWorkingSet needs 'requests' (the request rate), 'inflight' "
					+ "(requests held) or both, to fit the working set against");
		}
	}

}
