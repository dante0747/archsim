package io.archsim.model.codec;

import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Python's {@code dict[str, Any]}: any JSON object, kept as an {@link ObjectNode}. */
record ObjectAnyShape() implements Shape {

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		if (!in.isObject()) {
			errors.add("dict_type", at, Messages.DICT_TYPE);
			return null;
		}
		return in.deepCopy();
	}

	@Override
	public JsonNode write(@Nullable Object value) {
		return ((ObjectNode) Objects.requireNonNull(value)).deepCopy();
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		return NODES.objectNode().put("additionalProperties", true).put("type", "object");
	}
}
