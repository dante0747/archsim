package io.archsim.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Parts of the engine's JSON as the Python server picked them out of its dictionaries:
 * {@code {k: d.get(k) for k in keys}}, a missing key as {@code null}.
 */
final class JsonPicks {

	private JsonPicks() {
	}

	/**
	 * The keys of an object, each with its value or {@code null}.
	 * @param from the object
	 * @param keys the keys, in the order they are written
	 * @return the picked object
	 */
	static ObjectNode pick(JsonNode from, String... keys) {
		ObjectNode picked = JsonNodeFactory.instance.objectNode();
		for (String key : keys) {
			picked.set(key, value(from.path(key)));
		}
		return picked;
	}

	/**
	 * The same keys of each object of an array.
	 * @param from the array
	 * @param keys the keys
	 * @return an array of the picked objects
	 */
	static ArrayNode pickEach(JsonNode from, String... keys) {
		ArrayNode picked = JsonNodeFactory.instance.arrayNode();
		from.forEach((item) -> picked.add(pick(item, keys)));
		return picked;
	}

	/**
	 * A value, or {@code null} for a missing one, as {@code dict.get} gives it.
	 * @param value the value
	 * @return it, or a JSON null
	 */
	static JsonNode value(JsonNode value) {
		return value.isMissingNode() ? JsonNodeFactory.instance.nullNode() : value;
	}

}
