package io.archsim.engine.imports;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A resource of a Terraform plan or state, read as the Python importer reads it: each
 * field when it is used, so that a value of the wrong kind fails where Python failed.
 */
final class TerraformResource {

	private final ObjectNode resource;

	private TerraformResource(ObjectNode resource) {
		this.resource = resource;
	}

	/**
	 * A resource as the plan lists it.
	 * @param value the resource
	 * @return the resource
	 * @throws ImportShapeException when it is not a mapping
	 */
	static TerraformResource of(JsonNode value) {
		return new TerraformResource(PythonMappings.mapping(value));
	}

	/**
	 * {@code r.get("mode", "managed") == "managed"}: a resource the plan manages, not a
	 * data source.
	 * @return {@code true} for a managed resource
	 */
	boolean isManaged() {
		JsonNode mode = this.resource.get("mode");
		return mode == null || isText(mode, "managed");
	}

	/**
	 * {@code r["type"]}.
	 * @return its type, as written
	 * @throws ImportShapeException when it has none
	 */
	JsonNode type() {
		return PythonValues.item(this.resource, "type");
	}

	/**
	 * Whether the resource is of a type.
	 * @param type the type
	 * @return {@code true} when its type is that text
	 */
	boolean is(String type) {
		return isText(type(), type);
	}

	/**
	 * {@code r["address"]}.
	 * @return its address, as written
	 * @throws ImportShapeException when it has none
	 */
	JsonNode address() {
		return PythonValues.item(this.resource, "address");
	}

	/**
	 * {@code r.get("name")}.
	 * @return its name, as written; a missing node when it has none
	 */
	JsonNode name() {
		return this.resource.path("name");
	}

	/**
	 * {@code r["name"]}.
	 * @return its name, as written
	 * @throws ImportShapeException when it has none
	 */
	JsonNode requiredName() {
		return PythonValues.item(this.resource, "name");
	}

	/**
	 * {@code r.get("values") or {}}, read as a mapping.
	 * @return its values; an empty mapping when it has none
	 * @throws ImportShapeException when they are not a mapping
	 */
	ObjectNode values() {
		return PythonMappings.dict(this.resource.path("values"), "get");
	}

	private static boolean isText(JsonNode value, String text) {
		return value.isString() && text.equals(value.stringValue());
	}

	/**
	 * {@code _first(value)}: a mapping, or a list's first item when it is one.
	 * @param value the value
	 * @return the mapping; an empty one otherwise
	 */
	static ObjectNode first(JsonNode value) {
		if (value.isArray()) {
			return (!value.isEmpty() && value.get(0) instanceof ObjectNode item) ? item
					: JsonNodeFactory.instance.objectNode();
		}
		return (value instanceof ObjectNode mapping) ? mapping : JsonNodeFactory.instance.objectNode();
	}

}
