package io.archsim.engine.sizing;

import tools.jackson.databind.JsonNode;

/**
 * A spec a part is simulated with, and where it came from.
 *
 * @param value the spec's value, as the document has it
 * @param source set on the part, its auto size, or a scenario's override
 */
public record SpecValue(JsonNode value, SpecSource source) {

	/**
	 * Copies the value, so the record does not change.
	 */
	public SpecValue {
		value = value.deepCopy();
	}

	/**
	 * The spec's value, as a copy: a record does not change.
	 * @return a copy of the value
	 */
	@Override
	public JsonNode value() {
		return this.value.deepCopy();
	}

}
