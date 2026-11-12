package io.archsim.model.codec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.text.PythonText;

/**
 * A pydantic union with a discriminator, as a {@link Tagged} sealed interface: the
 * input's tag picks the record, and errors inside it are located under the tag
 * ({@code latencyMs.lognormal.p95}).
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
		if (!in.has(this.property)) {
			errors.add("union_tag_not_found", at, Messages.unionTagNotFound(this.property));
			return null;
		}
		JsonNode tag = in.get(this.property);
		RecordShape member = tag.isString() ? this.members.get(tag.stringValue()) : null;
		if (member == null) {
			errors.add("union_tag_invalid", at,
					Messages.unionTagInvalid(tagText(tag), this.property, List.copyOf(this.members.keySet())));
			return null;
		}
		return member.read(in, at.child(tag.stringValue()), errors);
	}

	// pydantic writes an unmatched tag lossily: three U+FFFD for each lone surrogate
	private static String tagText(JsonNode tag) {
		return tag.isString() ? Utf8Encoding.lossy(tag.stringValue()) : PythonText.str(tag);
	}

	@Override
	public JsonNode write(@Nullable Object value) {
		String tag = this.tags.get(Objects.requireNonNull(value).getClass());
		return Objects.requireNonNull(this.members.get(tag), "not a member of the union").write(value);
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		ObjectNode schema = NODES.objectNode();
		ObjectNode discriminator = schema.putObject("discriminator");
		ObjectNode mapping = discriminator.putObject("mapping");
		discriminator.put("propertyName", this.property);
		ArrayNode oneOf = schema.putArray("oneOf");
		this.members.forEach((tag, member) -> {
			ObjectNode reference = definitions.reference(member);
			mapping.put(tag, reference.get("$ref").stringValue());
			oneOf.add(reference);
		});
		return schema;
	}

}
