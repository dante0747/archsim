package io.archsim.benchmarks.resimulation;

import java.time.Clock;

import tools.jackson.databind.JsonNode;

/**
 * What names a development check or a range fit: the engine's label it is written under,
 * what produced it and the clock that dates it.
 *
 * @param label the label ({@link DevLabels}), which names the folder under
 * {@code results/dev/}
 * @param provenance the commit, trees, build and machine
 * ({@link io.archsim.benchmarks.provenance.Provenance})
 * @param clock the clock
 */
public record CheckStamp(String label, JsonNode provenance, Clock clock) {

	/**
	 * Copies the provenance.
	 */
	public CheckStamp {
		provenance = provenance.deepCopy();
	}

}
