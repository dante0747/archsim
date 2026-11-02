package io.archsim.model.codec;

import io.archsim.model.text.PythonText;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A pydantic union with a discriminator, as a {@link Tagged} sealed interface: the input's tag
 * picks the record, and errors inside it are located under the tag ({@code latencyMs.lognormal.p95}).
 */
final class UnionShape implements Shape {

    private final String property;
    private final Map<String, RecordShape> members;
    private final Map<Class<?>, String> tags;

    UnionShape(String property, Map<String, RecordShape> members, Map<Class<?>, String> tags) {
        this.property = property;
        this.members = new LinkedHashMap<>(members);
        this.tags = Map.copyOf(tags);
    }

    @Override
    public @Nullable Object read(JsonNode in, Location at, Errors errors) {
        if (!in.isObject()) {
            errors.add("model_attributes_type", at, Messages.MODEL_ATTRIBUTES_TYPE);
            return null;
        }
        if (!in.has(property)) {
            errors.add("union_tag_not_found", at, Messages.unionTagNotFound(property));
            return null;
        }
        JsonNode tag = in.get(property);
        RecordShape member = tag.isString() ? members.get(tag.stringValue()) : null;
        if (member == null) {
            errors.add(
                    "union_tag_invalid",
                    at,
                    Messages.unionTagInvalid(PythonText.str(tag), property, List.copyOf(members.keySet())));
            return null;
        }
        return member.read(in, at.child(tag.stringValue()), errors);
    }

    @Override
    public JsonNode write(@Nullable Object value) {
        String tag = tags.get(Objects.requireNonNull(value).getClass());
        return Objects.requireNonNull(members.get(tag), "not a member of the union")
                .write(value);
    }

    @Override
    public ObjectNode schema(Definitions definitions) {
        ObjectNode schema = NODES.objectNode();
        ObjectNode discriminator = schema.putObject("discriminator");
        ObjectNode mapping = discriminator.putObject("mapping");
        discriminator.put("propertyName", property);
        ArrayNode oneOf = schema.putArray("oneOf");
        members.forEach((tag, member) -> {
            ObjectNode reference = definitions.reference(member);
            mapping.put(tag, reference.get("$ref").stringValue());
            oneOf.add(reference);
        });
        return schema;
    }
}
