package io.archsim.model.codec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Python {@code dict[str, X]}, a Java {@link Map} with string keys, in the input's
 * order.
 *
 * @param value the shape of the values
 */
record MapShape(Shape value) implements Shape {

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		if (!in.isObject()) {
			errors.add("dict_type", at, Messages.DICT_TYPE);
			return null;
		}
		Map<String, @Nullable Object> entries = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : in.properties()) {
			entries.put(entry.getKey(), this.value.read(entry.getValue(), at.child(entry.getKey()), errors));
		}
		return Collections.unmodifiableMap(entries);
	}

	@Override
	public JsonNode write(@Nullable Object map) {
		ObjectNode object = NODES.objectNode();
		((Map<?, ?>) Objects.requireNonNull(map))
			.forEach((key, element) -> object.set((String) key, this.value.write(element)));
		return object;
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		ObjectNode schema = NODES.objectNode();
		schema.set("additionalProperties", this.value.schema(definitions));
		return schema.put("type", "object");
	}
}
