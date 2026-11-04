package io.archsim.engine.sizing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.node.ObjectNode;

/**
 * A design with its empty specs filled in by auto-sizing.
 *
 * @param document the design, as JSON
 * @param sizes the auto size of each part, by its id
 */
public record SizedDocument(ObjectNode document, Map<String, PartSize> sizes) {

	/**
	 * Copies the sizes, so the record does not change.
	 */
	public SizedDocument {
		sizes = Collections.unmodifiableMap(new LinkedHashMap<>(sizes));
	}

}
