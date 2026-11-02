package io.archsim.model.codec;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A pydantic {@code tuple}, as an {@link AsArray} record: a JSON array of exactly its components,
 * such as a trace point {@code [t, rps]}.
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

    @Override
    public @Nullable Object read(JsonNode in, Location at, Errors errors) {
        if (!in.isArray()) {
            errors.add("tuple_type", at, Messages.TUPLE_TYPE);
            return null;
        }
        if (in.size() > items.size()) {
            errors.add("too_long", at, Messages.tooLong("Tuple", items.size(), in.size()));
            return null;
        }
        int mark = errors.mark();
        @Nullable Object[] values = new @Nullable Object[items.size()];
        for (int i = 0; i < items.size(); i++) {
            if (i < in.size()) {
                values[i] = items.get(i).read(in.get(i), at.child(i), errors);
            } else {
                errors.add("missing", at.child(i), Messages.MISSING);
            }
        }
        if (errors.since(mark)) {
            return null;
        }
        try {
            return constructor.newInstance(values);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("a tuple record checks nothing itself", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public JsonNode write(@Nullable Object value) {
        ArrayNode array = NODES.arrayNode();
        for (int i = 0; i < items.size(); i++) {
            array.add(items.get(i).write(Reflection.get(accessors.get(i), Objects.requireNonNull(value))));
        }
        return array;
    }

    @Override
    public ObjectNode schema(Definitions definitions) {
        ObjectNode schema = NODES.objectNode().put("maxItems", items.size()).put("minItems", items.size());
        ArrayNode prefix = schema.putArray("prefixItems");
        items.forEach(item -> prefix.add(item.schema(definitions)));
        return schema.put("type", "array");
    }
}
