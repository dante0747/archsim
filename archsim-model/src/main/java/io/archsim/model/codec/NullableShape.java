package io.archsim.model.codec;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code X | None}: JSON {@code null}, or what the inner shape reads.
 *
 * @param inner the shape of the value when it is not {@code null}
 */
record NullableShape(Shape inner) implements Shape {

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		return in.isNull() ? null : this.inner.read(in, at, errors);
	}

	@Override
	public JsonNode write(@Nullable Object value) {
		return (value != null) ? this.inner.write(value) : NODES.nullNode();
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		ObjectNode schema = NODES.objectNode();
		schema.putArray("anyOf").add(this.inner.schema(definitions)).add(NODES.objectNode().put("type", "null"));
		return schema;
	}

}
