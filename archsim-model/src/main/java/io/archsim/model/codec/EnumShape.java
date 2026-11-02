package io.archsim.model.codec;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A pydantic {@code Literal} of strings, as a {@link JsonEnum}: exactly one of its
 * strings.
 */
final class EnumShape implements Shape {

	private final List<JsonEnum> constants;

	EnumShape(Class<?> type) {
		this.constants = Arrays.stream(type.getEnumConstants()).map(JsonEnum.class::cast).toList();
	}

	/** The constant whose JSON string is {@code json}, if there is one. */
	@Nullable JsonEnum constant(String json) {
		for (JsonEnum constant : constants) {
			if (constant.json().equals(json)) {
				return constant;
			}
		}
		return null;
	}

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		JsonEnum constant = in.isString() ? constant(in.stringValue()) : null;
		if (constant == null) {
			errors.add("literal_error", at, Messages.literal(Messages.quoted(strings())));
		}
		return constant;
	}

	private List<String> strings() {
		return constants.stream().map(JsonEnum::json).toList();
	}

	@Override
	public JsonNode write(@Nullable Object value) {
		return NODES.stringNode(((JsonEnum) Objects.requireNonNull(value)).json());
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		ObjectNode schema = NODES.objectNode();
		if (constants.size() == 1) {
			schema.put("const", constants.getFirst().json());
		}
		else {
			strings().forEach(schema.putArray("enum")::add);
		}
		return schema.put("type", "string");
	}

}
