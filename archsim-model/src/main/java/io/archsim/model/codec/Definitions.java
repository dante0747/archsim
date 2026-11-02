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

	/**
	 * A {@code $ref} to the record's definition, which is added the first time.
	 * @param record the record referred to
	 * @return the reference, such as {@code {"$ref": "#/$defs/Node"}}
	 */
	ObjectNode reference(RecordShape record) {
		if (!this.byName.containsKey(record.name())) {
			// a placeholder first, so a record that refers to itself does not loop
			this.byName.put(record.name(), JsonNodeFactory.instance.objectNode());
			this.byName.put(record.name(), record.definition(this));
		}
		return JsonNodeFactory.instance.objectNode().put("$ref", "#/$defs/" + record.name());
	}

	/**
	 * The definitions, by name.
	 * @return the {@code $defs} object, its records in name order
	 */
	ObjectNode toJson() {
		ObjectNode defs = JsonNodeFactory.instance.objectNode();
		this.byName.forEach(defs::set);
		return defs;
	}

	boolean isEmpty() {
		return this.byName.isEmpty();
	}

}
