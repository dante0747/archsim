package io.archsim.model.codec;

import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The records a JSON Schema refers to, collected for its {@code $defs} as they are met.
 */
final class Definitions {

	private final Map<String, ObjectNode> byName = new TreeMap<>();

	/** A {@code $ref} to the record's definition, which is added the first time. */
	ObjectNode reference(RecordShape record) {
		if (!byName.containsKey(record.name())) {
			byName.put(record.name(), JsonNodeFactory.instance.objectNode()); // placeholder:
																				// no
																				// loops
			byName.put(record.name(), record.definition(this));
		}
		return JsonNodeFactory.instance.objectNode().put("$ref", "#/$defs/" + record.name());
	}

	/** The definitions, by name. */
	ObjectNode toJson() {
		ObjectNode defs = JsonNodeFactory.instance.objectNode();
		byName.forEach(defs::set);
		return defs;
	}

	boolean isEmpty() {
		return byName.isEmpty();
	}

}
