package io.archsim.apitests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** JSON in tests: parse, write, build and pick values out. */
public final class Json {

	public static final JsonMapper MAPPER = JsonMapper.builder().build();

	private Json() {
	}

	public static JsonNode parse(String text) {
		return MAPPER.readTree(text);
	}

	/** A JSON file, e.g. an example or a fixture. */
	public static JsonNode read(Path file) {
		try {
			return parse(Files.readString(file, StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** {@code value} as JSON text; a string is written as a JSON string. */
	public static String write(Object value) {
		return MAPPER.writeValueAsString(value);
	}

	/** Any value (a node, a map, a list, a string, a number, null) as a node. */
	public static JsonNode tree(@Nullable Object value) {
		if (value == null) {
			return JsonNodeFactory.instance.nullNode();
		}
		if (value instanceof JsonNode node) {
			return node;
		}
		return MAPPER.valueToTree(value);
	}

	/** An object from names and values: {@code object("name", "x", "seeds", 3)}. */
	public static ObjectNode object(@Nullable Object... namesAndValues) {
		if (namesAndValues.length % 2 != 0) {
			throw new IllegalArgumentException("names and values come in pairs");
		}
		ObjectNode node = MAPPER.createObjectNode();
		for (int i = 0; i < namesAndValues.length; i += 2) {
			node.set(String.valueOf(namesAndValues[i]), tree(namesAndValues[i + 1]));
		}
		return node;
	}

	/** An array of the given values. */
	public static ArrayNode array(@Nullable Object... values) {
		ArrayNode node = MAPPER.createArrayNode();
		for (Object value : values) {
			node.add(tree(value));
		}
		return node;
	}

	/** A deep copy of an object, to change without touching the original. */
	public static ObjectNode copy(JsonNode node) {
		if (!(node.deepCopy() instanceof ObjectNode copy)) {
			throw new IllegalArgumentException("not a JSON object: " + node);
		}
		return copy;
	}

	/** The elements of an array (or nothing, for anything else). */
	public static List<JsonNode> items(JsonNode array) {
		List<JsonNode> out = new ArrayList<>();
		if (array.isArray()) {
			array.forEach(out::add);
		}
		return out;
	}

	/**
	 * {@code field} of each element of an array, as text:
	 * {@code [x["id"] for x in array]}.
	 */
	public static List<String> strings(JsonNode array, String field) {
		return items(array).stream().map(x -> x.path(field).asString()).toList();
	}

	/** The first element of an array whose {@code field} is {@code value}. */
	public static JsonNode find(JsonNode array, String field, String value) {
		return items(array).stream()
			.filter(x -> value.equals(x.path(field).asString(null)))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no element with " + field + "=" + value + " in " + array));
	}

}
