package io.archsim.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Reading a golden entry's input and writing its output as the golden files record values
 * (parity/README.md): numbers may be the strings {@code "Infinity"}, {@code "-Infinity"}
 * and {@code "NaN"}, and Python's {@code None} is JSON {@code null}.
 */
final class GoldenJson {

	static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private GoldenJson() {
	}

	/**
	 * A number the golden file may spell as a string, or {@code null} for {@code None}.
	 */
	static @Nullable Double readNullableNumber(JsonNode value) {
		if (value.isNull()) {
			return null;
		}
		return value.isString() ? Double.parseDouble(value.stringValue()) : value.doubleValue();
	}

	/** A number the golden file may spell as a string. */
	static double readNumber(JsonNode value) {
		Double number = readNullableNumber(value);
		if (number == null) {
			throw new IllegalArgumentException("not a number: null");
		}
		return number;
	}

	/** A list of numbers, {@code None} kept as {@code null}. */
	static List<@Nullable Double> readNullableNumbers(JsonNode array) {
		List<@Nullable Double> numbers = new ArrayList<>();
		array.forEach((value) -> numbers.add(readNullableNumber(value)));
		return numbers;
	}

	/** A string, or {@code null} for {@code None}. */
	static @Nullable String readNullableText(JsonNode value) {
		return value.isNull() ? null : value.stringValue();
	}

	static JsonNode text(String text) {
		return NODES.stringNode(text);
	}

	static JsonNode number(double number) {
		return NODES.numberNode(number);
	}

	/** A float, or {@code None} when there is none. */
	static JsonNode number(OptionalDouble number) {
		return number.isPresent() ? number(number.getAsDouble()) : NODES.nullNode();
	}

	/** A value, or {@code None} when there is none. */
	static JsonNode orNone(Optional<? extends JsonNode> value) {
		return value.isPresent() ? value.get() : NODES.nullNode();
	}

	static ArrayNode numbers(List<Double> numbers) {
		ArrayNode array = NODES.arrayNode();
		numbers.forEach(array::add);
		return array;
	}

}
