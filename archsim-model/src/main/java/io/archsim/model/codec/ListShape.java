package io.archsim.model.codec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Python {@code list}, a Java {@link List}. As pydantic does, a list longer than its
 * maximum is reported as that alone; otherwise every item is checked, then the minimum,
 * then a {@link UniqueItems} validator.
 */
record ListShape(Shape item, @Nullable Integer minItems, @Nullable Integer maxItems,
		@Nullable String uniqueMessage) implements Shape {

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		if (!in.isArray()) {
			errors.add("list_type", at, Messages.LIST_TYPE);
			return null;
		}
		if (maxItems != null && in.size() > maxItems) {
			errors.add("too_long", at, Messages.tooLong("List", maxItems, in.size()));
			return null;
		}
		int mark = errors.mark();
		List<@Nullable Object> items = new ArrayList<>(in.size());
		for (int i = 0; i < in.size(); i++) {
			items.add(item.read(in.get(i), at.child(i), errors));
		}
		if (errors.since(mark)) {
			return null;
		}
		if (minItems != null && in.size() < minItems) {
			errors.add("too_short", at, Messages.tooShort("List", minItems, in.size()));
			return null;
		}
		if (uniqueMessage != null && new HashSet<>(items).size() < items.size()) {
			errors.add("value_error", at, Messages.valueError(uniqueMessage));
			return null;
		}
		return items;
	}

	@Override
	public JsonNode write(@Nullable Object value) {
		ArrayNode array = NODES.arrayNode();
		for (Object element : (List<?>) Objects.requireNonNull(value)) {
			array.add(item.write(element));
		}
		return array;
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		ObjectNode schema = NODES.objectNode();
		schema.set("items", item.schema(definitions));
		if (minItems != null) {
			schema.put("minItems", minItems);
		}
		if (maxItems != null) {
			schema.put("maxItems", maxItems);
		}
		return schema.put("type", "array");
	}
}
