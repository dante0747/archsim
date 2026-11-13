package io.archsim.benchmarks.inputs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A model input as the calibration writes it: its value, then how it was found.
 */
final class Input {

	private Input() {
	}

	/**
	 * An input.
	 * @param value its value: a number or a distribution
	 * @param how how it was found, in words
	 * @return the input
	 */
	static ObjectNode of(JsonNode value, String how) {
		ObjectNode input = JsonNodeFactory.instance.objectNode().set("value", value);
		return input.put("how", how);
	}

}
