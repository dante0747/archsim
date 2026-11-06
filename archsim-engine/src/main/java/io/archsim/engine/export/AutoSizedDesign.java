package io.archsim.engine.export;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.node.ObjectNode;

/**
 * A design with its empty CPU, memory and storage specs filled with its auto sizes.
 *
 * @param document the design, auto-sized; a copy goes in and comes out
 * @param cpuNeeds the CPU each part whose CPU was auto-sized needs, by its id, in the
 * design's order
 */
public record AutoSizedDesign(ObjectNode document, Map<String, Double> cpuNeeds) {

	/**
	 * Copies the design and the needs, so the record does not change.
	 */
	public AutoSizedDesign {
		document = document.deepCopy();
		cpuNeeds = Collections.unmodifiableMap(new LinkedHashMap<>(cpuNeeds));
	}

	/**
	 * The design, as a copy: a record does not change.
	 * @return a copy of the design
	 */
	@Override
	public ObjectNode document() {
		return this.document.deepCopy();
	}

}
