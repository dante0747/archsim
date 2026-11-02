package io.archsim.model.codec;

import java.lang.reflect.AnnotatedType;
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
 *
 * @param item the shape of the items
 * @param minItems the fewest items allowed, if there is a minimum
 * @param maxItems the most items allowed, if there is a maximum
 * @param uniqueMessage the error a repeated item gives, if the items must differ
 */
record ListShape(Shape item, @Nullable Integer minItems, @Nullable Integer maxItems,
		@Nullable String uniqueMessage) implements Shape {

	/**
	 * The shape of a list type, from its {@link MinItems}, {@link MaxItems} and
	 * {@link UniqueItems}.
	 * @param item the shape of the items
	 * @param annotated the list type, with its annotations
	 * @return the list's shape
	 */
	static ListShape of(Shape item, AnnotatedType annotated) {
		MinItems min = annotated.getAnnotation(MinItems.class);
		MaxItems max = annotated.getAnnotation(MaxItems.class);
		UniqueItems unique = annotated.getAnnotation(UniqueItems.class);
		return new ListShape(item, (min != null) ? min.value() : null, (max != null) ? max.value() : null,
				(unique != null) ? unique.value() : null);
	}

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		if (!in.isArray()) {
			errors.add("list_type", at, Messages.LIST_TYPE);
			return null;
		}
		if (this.maxItems != null && in.size() > this.maxItems) {
			errors.add("too_long", at, Messages.tooLong("List", this.maxItems, in.size()));
			return null;
		}
		int mark = errors.mark();
		List<@Nullable Object> items = new ArrayList<>(in.size());
		for (int i = 0; i < in.size(); i++) {
			items.add(this.item.read(in.get(i), at.child(i), errors));
		}
		if (errors.since(mark)) {
			return null;
		}
		return checked(items, at, errors);
	}

	/**
	 * The valid items, when there are enough of them and, if they must differ, they do.
	 * @param items the items read
	 * @param at where the list is in the input
	 * @param errors where the first failed check is added
	 * @return the items, or {@code null} when a check fails
	 */
	private @Nullable List<@Nullable Object> checked(List<@Nullable Object> items, Location at, Errors errors) {
		if (this.minItems != null && items.size() < this.minItems) {
			errors.add("too_short", at, Messages.tooShort("List", this.minItems, items.size()));
			return null;
		}
		if (this.uniqueMessage != null && new HashSet<>(items).size() < items.size()) {
			errors.add("value_error", at, Messages.valueError(this.uniqueMessage));
			return null;
		}
		return items;
	}

	@Override
	public JsonNode write(@Nullable Object value) {
		ArrayNode array = NODES.arrayNode();
		for (Object element : (List<?>) Objects.requireNonNull(value)) {
			array.add(this.item.write(element));
		}
		return array;
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		ObjectNode schema = NODES.objectNode();
		schema.set("items", this.item.schema(definitions));
		if (this.minItems != null) {
			schema.put("minItems", this.minItems);
		}
		if (this.maxItems != null) {
			schema.put("maxItems", this.maxItems);
		}
		return schema.put("type", "array");
	}

}
