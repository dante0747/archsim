package io.archsim.model.codec;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A pydantic {@code Literal} of integers ({@link Choice}): one of them, given as an
 * integer, a float equal to it, or a boolean (Python's {@code True == 1}), but never as a
 * string.
 *
 * @param choices the integers allowed, in the order messages list them
 */
record IntChoiceShape(List<Integer> choices) implements Shape {

	IntChoiceShape {
		choices = List.copyOf(choices);
	}

	static IntChoiceShape of(Choice[] choices) {
		return new IntChoiceShape(Arrays.stream(choices).map(Choice::value).toList());
	}

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		if (in.isBoolean() || in.isNumber()) {
			double number = in.isBoolean() ? (in.booleanValue() ? 1 : 0) : in.doubleValue();
			for (int choice : this.choices) {
				if (number == choice) {
					return choice;
				}
			}
		}
		errors.add("literal_error", at, Messages.literal(this.choices.stream().map(String::valueOf).toList()));
		return null;
	}

	@Override
	public JsonNode write(@Nullable Object value) {
		return NODES.numberNode((Integer) Objects.requireNonNull(value));
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		ObjectNode schema = NODES.objectNode();
		this.choices.forEach(schema.putArray("enum")::add);
		return schema.put("type", "integer");
	}
}
