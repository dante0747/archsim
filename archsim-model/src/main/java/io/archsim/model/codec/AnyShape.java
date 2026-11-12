package io.archsim.model.codec;

import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Python's {@code Any}: any JSON at all, {@code null} included, kept as a
 * {@link JsonNode}. It is written as pydantic's JSON mode writes it, with every float
 * that is not finite as {@code null}.
 */
record AnyShape() implements Shape {

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		return in.deepCopy();
	}

	@Override
	public JsonNode write(@Nullable Object value) {
		return jsonModeCopy((JsonNode) Objects.requireNonNull(value));
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		return NODES.objectNode();
	}

	/**
	 * A copy of a value as pydantic's JSON mode writes an {@code Any} value: a float that
	 * is not finite, however deep, is {@code null}. A float field keeps it
	 * ({@link FloatShape}).
	 * @param value the value
	 * @return its copy in JSON mode
	 */
	static JsonNode jsonModeCopy(JsonNode value) {
		if (value.isDouble()) {
			return Double.isFinite(value.doubleValue()) ? value : NODES.nullNode();
		}
		if (value.isObject()) {
			ObjectNode copy = NODES.objectNode();
			value.properties().forEach((property) -> copy.set(property.getKey(), jsonModeCopy(property.getValue())));
			return copy;
		}
		if (value.isArray()) {
			ArrayNode copy = NODES.arrayNode();
			value.forEach((item) -> copy.add(jsonModeCopy(item)));
			return copy;
		}
		return value.deepCopy();
	}

}
