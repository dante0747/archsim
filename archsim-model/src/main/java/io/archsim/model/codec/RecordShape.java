package io.archsim.model.codec;

import io.archsim.model.text.PythonText;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A pydantic model with {@code extra="forbid"}, as a Java record: a JSON object of its fields.
 *
 * <p>Reading checks the fields in declaration order, each looked up by its JSON name and then by
 * its Python name, and reports every unknown key after them, in the input's order. When all of it
 * is valid it calls the canonical constructor; an {@link IllegalArgumentException} from there is
 * the record's own check failing (a pydantic model validator), reported as a {@code value_error}
 * at the object.
 */
final class RecordShape implements Shape {

    /** The tag of a record in a {@link Tagged} union: the property and the record's value. */
    record TagField(String property, String value) {}

    private final String name;
    private final @Nullable String description;
    private final @Nullable TagField tag;
    private final List<Field> fields;
    private final Constructor<?> constructor;

    RecordShape(
            String name,
            @Nullable String description,
            @Nullable TagField tag,
            List<Field> fields,
            Constructor<?> constructor) {
        this.name = name;
        this.description = description;
        this.tag = tag;
        this.fields = List.copyOf(fields);
        this.constructor = constructor;
    }

    /** The model's name, as messages and the JSON Schema use it. */
    String name() {
        return name;
    }

    List<Field> fields() {
        return fields;
    }

    @Override
    public @Nullable Object read(JsonNode in, Location at, Errors errors) {
        if (!in.isObject()) {
            errors.add("model_type", at, Messages.modelType(name));
            return null;
        }
        int mark = errors.mark();
        Set<String> used = new HashSet<>();
        if (tag != null && in.has(tag.property())) {
            used.add(tag.property());
            JsonNode value = in.get(tag.property());
            if (!value.isString() || !value.stringValue().equals(tag.value())) {
                errors.add(
                        "literal_error",
                        at.child(tag.property()),
                        Messages.literal(List.of(PythonText.repr(tag.value()))));
            }
        }
        @Nullable Object[] values = new @Nullable Object[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            String key = in.has(field.name()) ? field.name() : in.has(field.pythonName()) ? field.pythonName() : null;
            if (key != null) {
                used.add(key);
                values[i] = field.shape().read(in.get(key), at.child(key), errors);
            } else if (field.required()) {
                errors.add("missing", at.child(field.name()), Messages.MISSING);
            } else {
                Object fallback = field.defaultValue();
                values[i] = fallback instanceof JsonNode node ? node.deepCopy() : fallback;
            }
        }
        for (Map.Entry<String, JsonNode> property : in.properties()) {
            if (!used.contains(property.getKey())) {
                errors.add("extra_forbidden", at.child(property.getKey()), Messages.EXTRA_FORBIDDEN);
            }
        }
        if (errors.since(mark)) {
            return null;
        }
        return construct(values, at, errors);
    }

    /** A record of these field values; a value error when the record rejects them. */
    @Nullable
    Object construct(@Nullable Object[] values, Location at, Errors errors) {
        try {
            return constructor.newInstance(values);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IllegalArgumentException rejected && rejected.getMessage() != null) {
                errors.add("value_error", at, Messages.valueError(rejected.getMessage()));
                return null;
            }
            throw new IllegalStateException("record " + name + " failed to construct", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("record " + name + " cannot be constructed", e);
        }
    }

    @Override
    public JsonNode write(@Nullable Object record) {
        Objects.requireNonNull(record);
        ObjectNode object = NODES.objectNode();
        if (tag != null) {
            object.put(tag.property(), tag.value());
        }
        for (Field field : fields) {
            Object value = Reflection.get(field.accessor(), record);
            if (field.omitWhenDefault() && Objects.equals(value, field.defaultValue())) {
                continue;
            }
            object.set(field.name(), field.shape().write(value));
        }
        return object;
    }

    @Override
    public ObjectNode schema(Definitions definitions) {
        return definitions.reference(this);
    }

    /** The record's JSON Schema definition, as pydantic writes a model's. */
    ObjectNode definition(Definitions definitions) {
        ObjectNode definition = NODES.objectNode().put("additionalProperties", false);
        if (description != null) {
            definition.put("description", description);
        }
        ObjectNode properties = definition.putObject("properties");
        if (tag != null) {
            properties
                    .putObject(tag.property())
                    .put("const", tag.value())
                    .put("default", tag.value())
                    .put("title", PythonText.title(tag.property()))
                    .put("type", "string");
        }
        ArrayNode required = NODES.arrayNode();
        for (Field field : fields) {
            properties.set(field.name(), property(field, definitions));
            if (field.required()) {
                required.add(field.name());
            }
        }
        if (!required.isEmpty()) {
            definition.set("required", required);
        }
        return definition.put("title", name).put("type", "object");
    }

    /** A field's schema: its value's, with the title, description, default and extras pydantic adds. */
    private static ObjectNode property(Field field, Definitions definitions) {
        ObjectNode schema = field.shape().schema(definitions);
        boolean reference =
                field.shape() instanceof RecordShape || field.shape() instanceof NullableShape(RecordShape inner);
        if (!reference) {
            schema.put("title", PythonText.title(field.name()));
        }
        if (field.description() != null) {
            schema.put("description", field.description());
        }
        if (field.defaultInSchema()) {
            schema.set("default", field.shape().write(field.defaultValue()));
        }
        if (field.extra() != null) {
            schema.setAll(field.extra());
        }
        return schema;
    }
}
