package io.archsim.model.codec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Python {@code dict[str, X]}, a Java {@link Map} with string keys, or a
 * {@code dict[Literal[...], X]}, one keyed by a {@link JsonEnum}, in the input's order.
 *
 * @param key the shape of the keys when they are a {@link JsonEnum}; {@code null} for
 * strings
 * @param value the shape of the values
 */
record MapShape(@Nullable EnumShape key, Shape value) implements Shape {

	/** Where pydantic locates a key's error, under the key's own location. */
	private static final String KEY = "[key]";

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		if (!in.isObject()) {
			errors.add("dict_type", at, Messages.DICT_TYPE);
			return null;
		}
		Map<Object, @Nullable Object> entries = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : in.properties()) {
			Location here = at.child(entry.getKey());
			Object mapKey = readKey(entry.getKey(), here, errors);
			Object element = this.value.read(entry.getValue(), here, errors);
			if (mapKey != null) {
				entries.put(mapKey, element);
			}
		}
		return Collections.unmodifiableMap(entries);
	}

	/**
	 * A key, as text or as the constant it names.
	 * @param key the key in the input
	 * @param at where its value is in the input
	 * @param errors where an unknown constant is added
	 * @return the key; {@code null} when it names no constant
	 */
	private @Nullable Object readKey(String key, Location at, Errors errors) {
		if (this.key == null) {
			return key;
		}
		return this.key.read(NODES.stringNode(key), at.child(KEY), errors);
	}

	@Override
	public JsonNode write(@Nullable Object map) {
		ObjectNode object = NODES.objectNode();
		((Map<?, ?>) Objects.requireNonNull(map)).forEach((key, element) -> object
			.set((key instanceof JsonEnum constant) ? constant.json() : (String) key, this.value.write(element)));
		return object;
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		ObjectNode schema = NODES.objectNode();
		schema.set("additionalProperties", this.value.schema(definitions));
		if (this.key != null) {
			schema.set("propertyNames", this.key.choices());
		}
		return schema.put("type", "object");
	}
}
