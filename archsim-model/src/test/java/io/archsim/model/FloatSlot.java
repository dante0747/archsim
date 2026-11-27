package io.archsim.model;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.model.codec.AsArray;
import io.archsim.model.codec.Location;

/**
 * One float of a record the codec reads: the component that holds it, and the input that
 * puts a value there.
 *
 * @param holder the record
 * @param index which of the record's components the float is
 */
record FloatSlot(Class<? extends Record> holder, int index) {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/**
	 * The smallest input of the record that holds a value in this slot: an object with
	 * the one field, or for a record written as an array, the array with zeros elsewhere.
	 * @param value the value to read as the float
	 * @return the record's input
	 */
	JsonNode inputWith(JsonNode value) {
		if (!writtenAsArray()) {
			return NODES.objectNode().set(name(), value);
		}
		ArrayNode array = NODES.arrayNode();
		for (int i = 0; i < this.holder.getRecordComponents().length; i++) {
			array.add((i == this.index) ? value : NODES.numberNode(0));
		}
		return array;
	}

	/**
	 * Where the codec reports an error in this slot.
	 * @return the field's name, or its index in a record written as an array
	 */
	Location location() {
		return writtenAsArray() ? Location.ROOT.child(this.index) : Location.ROOT.child(name());
	}

	private boolean writtenAsArray() {
		return this.holder.isAnnotationPresent(AsArray.class);
	}

	private String name() {
		return this.holder.getRecordComponents()[this.index].getName();
	}

	@Override
	public String toString() {
		return this.holder.getSimpleName() + (writtenAsArray() ? "[" + this.index + "]" : "." + name());
	}

}
