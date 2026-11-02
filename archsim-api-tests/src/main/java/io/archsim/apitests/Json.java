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

	/** The mapper every helper here uses: Jackson's defaults. */
	public static final JsonMapper MAPPER = JsonMapper.builder().build();

	private Json() {
	}

	public static JsonNode parse(String text) {
		return MAPPER.readTree(text);
	}

	/**
	 * Reads a JSON file, e.g. an example or a fixture.
	 * @param file the file, in UTF-8
	 * @return the file's JSON
	 */
	public static JsonNode read(Path file) {
		try {
			return parse(Files.readString(file, StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Writes a value as JSON text.
	 * @param value a node, a map, a list...; a string is written as a JSON string
	 * @return the JSON text
	 */
	public static String write(Object value) {
		return MAPPER.writeValueAsString(value);
	}

	/**
	 * Turns any value into a node.
	 * @param value a node, a map, a list, a string, a number, or null
	 * @return {@code value} itself when it is a node, otherwise its JSON
	 */
	public static JsonNode tree(@Nullable Object value) {
		if (value == null) {
			return JsonNodeFactory.instance.nullNode();
		}
		if (value instanceof JsonNode node) {
			return node;
		}
		return MAPPER.valueToTree(value);
	}

	/**
	 * Builds an object from names and values: {@code object("name", "x", "seeds", 3)}.
	 * @param namesAndValues each field's name followed by its value (see {@link #tree})
	 * @return the object, with its fields in the order given
	 */
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

	/**
	 * Builds an array of the given values.
	 * @param values the elements (see {@link #tree})
	 * @return the array, with its elements in the order given
	 */
	public static ArrayNode array(@Nullable Object... values) {
		ArrayNode node = MAPPER.createArrayNode();
		for (Object value : values) {
			node.add(tree(value));
		}
		return node;
	}

	/**
	 * Copies an object deeply, to change without touching the original.
	 * @param node the object to copy
	 * @return the copy
	 */
	public static ObjectNode copy(JsonNode node) {
		if (!(node.deepCopy() instanceof ObjectNode copy)) {
			throw new IllegalArgumentException("not a JSON object: " + node);
		}
		return copy;
	}

	/**
	 * Returns the elements of an array.
	 * @param array the array; anything else has no elements
	 * @return the elements, in order
	 */
	public static List<JsonNode> items(JsonNode array) {
		List<JsonNode> elements = new ArrayList<>();
		if (array.isArray()) {
			array.forEach(elements::add);
		}
		return elements;
	}

	/**
	 * Picks a field out of each element of an array, as text:
	 * {@code [x["id"] for x in array]}.
	 * @param array the array
	 * @param field the field to pick
	 * @return the field's text in each element, in order
	 */
	public static List<String> strings(JsonNode array, String field) {
		return items(array).stream().map((element) -> element.path(field).asString()).toList();
	}

	/**
	 * Finds the first element of an array whose field has a given value; fails the test
	 * when there is none.
	 * @param array the array to search
	 * @param field the field to compare
	 * @param value the text the field must have
	 * @return the element
	 */
	public static JsonNode find(JsonNode array, String field, String value) {
		return items(array).stream()
			.filter((element) -> value.equals(element.path(field).asString(null)))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no element with " + field + "=" + value + " in " + array));
	}

}
