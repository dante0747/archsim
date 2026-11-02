package io.archsim.model.codec;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Python's {@code Any}: any JSON at all, {@code null} included, kept as a
 * {@link JsonNode}.
 */
record AnyShape() implements Shape {

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		return in.deepCopy();
	}

	@Override
	public JsonNode write(@Nullable Object value) {
		return ((JsonNode) Objects.requireNonNull(value)).deepCopy();
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		return NODES.objectNode();
	}
}
