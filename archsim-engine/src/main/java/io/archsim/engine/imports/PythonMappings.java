package io.archsim.engine.imports;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Python's ways with a value read as a mapping, where the importers inherited them:
 * {@code (value or {}).get}, a bare {@code value.get}, {@code mapping.get(key, {})},
 * {@code dict()} and the text of a mapping's items. Where Python fails on a value of the
 * wrong kind, these fail with Python's words, as {@link PythonValues} does.
 */
final class PythonMappings {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private PythonMappings() {
	}

	/**
	 * {@code value or {}}, which is then read as a mapping.
	 * @param value the value
	 * @param attribute what is read of it, for the words of a failure: {@code get},
	 * {@code items}
	 * @return the mapping; an empty one when the value counts as false
	 * @throws ImportShapeException when a value that counts as true is not a mapping
	 */
	static ObjectNode dict(JsonNode value, String attribute) {
		if (!PythonValues.truthy(value)) {
			return NODES.objectNode();
		}
		if (value instanceof ObjectNode mapping) {
			return mapping;
		}
		throw new ImportShapeException(
				"'" + PythonValues.type(value) + "' object has no attribute '" + attribute + "'");
	}

	/**
	 * A value whose {@code get} Python calls as it is: unlike {@code (value or {}).get},
	 * a value that counts as false fails too.
	 * @param value the value
	 * @return the mapping
	 * @throws ImportShapeException when it is not a mapping
	 */
	static ObjectNode mapping(JsonNode value) {
		if (value instanceof ObjectNode mapping) {
			return mapping;
		}
		throw new ImportShapeException("'" + PythonValues.type(value) + "' object has no attribute 'get'");
	}

	/**
	 * {@code dict(value or {})}: a mapping, or a list of pairs, each a list, a mapping or
	 * a text of two items.
	 * @param value the value
	 * @return a new mapping, keys as their text
	 * @throws ImportRefusedException with Python's words for an item that is not two
	 * items
	 * @throws ImportShapeException for an item, or a value, that is not a sequence, or a
	 * key that cannot be one
	 */
	static ObjectNode dictOf(JsonNode value) {
		if (value.isObject() || !PythonValues.truthy(value)) {
			return dict(value, "keys").deepCopy();
		}
		ObjectNode mapping = NODES.objectNode();
		List<JsonNode> items = PythonValues.items(value);
		for (int index = 0; index < items.size(); index++) {
			List<JsonNode> pair = pair(items.get(index), index);
			if (pair.get(0).isContainer()) {
				throw PythonValues.unhashable(pair.get(0));
			}
			mapping.set(PythonValues.str(pair.get(0)), pair.get(1));
		}
		return mapping;
	}

	/**
	 * {@code {str(k): str(v) for k, v in mapping.items()}}.
	 * @param mapping a mapping's value, {@code value or {}}
	 * @return the mapping's keys and values as text, in order
	 */
	static Map<String, String> texts(JsonNode mapping) {
		Map<String, String> texts = new LinkedHashMap<>();
		dict(mapping, "items").properties()
			.forEach((entry) -> texts.put(entry.getKey(), PythonValues.str(entry.getValue())));
		return texts;
	}

	/**
	 * {@code mapping.get(key, {})} then read as a mapping: unlike {@code value or {}}, a
	 * value written as {@code null} is no mapping.
	 * @param mapping the mapping
	 * @param key the key
	 * @return the value at the key; an empty mapping when there is none
	 * @throws ImportShapeException when the value is not a mapping
	 */
	static ObjectNode getMapping(ObjectNode mapping, String key) {
		JsonNode value = mapping.get(key);
		return (value != null) ? mapping(value) : NODES.objectNode();
	}

	/**
	 * An item of the pairs {@code dict()} reads.
	 * @param item the item
	 * @param index its place, for the words of a failure
	 * @return its two items
	 * @throws ImportRefusedException with Python's words when it is not two items
	 * @throws ImportShapeException when it is not a sequence
	 */
	private static List<JsonNode> pair(JsonNode item, int index) {
		if (!item.isContainer() && !item.isString()) {
			throw new ImportShapeException(
					"cannot convert dictionary update sequence element #" + index + " to a sequence");
		}
		List<JsonNode> pair = PythonValues.items(item);
		if (pair.size() != 2) {
			throw new ImportRefusedException(
					"dictionary update sequence element #" + index + " has length " + pair.size() + "; 2 is required");
		}
		return pair;
	}

}
