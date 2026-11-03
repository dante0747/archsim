package io.archsim.model.codec;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A pydantic {@code tuple}, as an {@link AsArray} record: a JSON array of exactly its
 * components, such as a trace point {@code [t, rps]}.
 */
final class TupleShape implements Shape {

	private final List<Shape> items;

	private final List<Method> accessors;

	private final Constructor<?> constructor;

	TupleShape(List<Shape> items, List<Method> accessors, Constructor<?> constructor) {
		this.items = List.copyOf(items);
		this.accessors = List.copyOf(accessors);
		this.constructor = constructor;
	}

	/**
	 * The shape of an {@link AsArray} record: an item for each of its components.
	 * @param type the record
	 * @return its shape
	 */
	static TupleShape of(Class<?> type) {
		RecordComponent[] components = type.getRecordComponents();
		List<Shape> items = Arrays.stream(components)
			.map((component) -> TypeShapes.of(component.getAnnotatedType()))
			.toList();
		List<Method> accessors = Arrays.stream(components)
			.map((component) -> Reflection.accessible(component.getAccessor()))
			.toList();
		return new TupleShape(items, accessors, Reflection.canonicalConstructor(type, components));
	}

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		if (!in.isArray()) {
			errors.add("tuple_type", at, Messages.TUPLE_TYPE);
			return null;
		}
		if (in.size() > this.items.size()) {
			errors.add("too_long", at, Messages.tooLong("Tuple", this.items.size(), in.size()));
			return null;
		}
		int mark = errors.mark();
		@Nullable Object[] values = readItems(in, at, errors);
		if (errors.since(mark)) {
			return null;
		}
		return construct(values);
	}

	/**
	 * The value of each component, in order; one the array does not reach is missing.
	 * @param in the JSON array read
	 * @param at where the array is in the input
	 * @param errors where the errors in the items are added
	 * @return the values, {@code null} where an item is missing
	 */
	private @Nullable Object[] readItems(JsonNode in, Location at, Errors errors) {
		@Nullable Object[] values = new @Nullable Object[this.items.size()];
		for (int i = 0; i < this.items.size(); i++) {
			if (i < in.size()) {
				values[i] = this.items.get(i).read(in.get(i), at.child(i), errors);
			}
			else {
				errors.add("missing", at.child(i), Messages.MISSING);
			}
		}
		return values;
	}

	private Object construct(@Nullable Object[] values) {
		try {
			return this.constructor.newInstance(values);
		}
		catch (InvocationTargetException ex) {
			throw new IllegalStateException("a tuple record checks nothing itself", ex.getCause());
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Override
	public JsonNode write(@Nullable Object value) {
		ArrayNode array = NODES.arrayNode();
		for (int i = 0; i < this.items.size(); i++) {
			array.add(this.items.get(i).write(Reflection.get(this.accessors.get(i), Objects.requireNonNull(value))));
		}
		return array;
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		ObjectNode schema = NODES.objectNode().put("maxItems", this.items.size()).put("minItems", this.items.size());
		ArrayNode prefix = schema.putArray("prefixItems");
		this.items.forEach((item) -> prefix.add(item.schema(definitions)));
		return schema.put("type", "array");
	}

}
