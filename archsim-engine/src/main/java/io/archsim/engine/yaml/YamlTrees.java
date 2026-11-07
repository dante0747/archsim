package io.archsim.engine.yaml;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.text.PythonText;

/**
 * The values SnakeYAML's safe constructor builds as a Jackson tree, Python's types as
 * JSON's kinds: a mapping's keys as the text Python's {@code str()} gives them
 * ({@code 1}, {@code True}, {@code None}), a set as a list.
 */
final class YamlTrees {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private YamlTrees() {
	}

	/**
	 * A value as a Jackson tree.
	 * @param value what the constructor built
	 * @return the tree
	 * @throws InvalidYamlException for a mapping keyed by a list or a mapping, which
	 * Python cannot hash
	 */
	static JsonNode of(@Nullable Object value) {
		return switch (value) {
			case null -> NODES.nullNode();
			case String text -> NODES.stringNode(text);
			case Boolean bool -> NODES.booleanNode(bool);
			case Integer number -> NODES.numberNode(number);
			case Long number -> NODES.numberNode(number);
			case BigInteger number -> whole(number);
			case Double number -> NODES.numberNode(number);
			case Map<?, ?> mapping -> mapping(mapping);
			case Collection<?> items -> sequence(items);
			case Object[] pair -> sequence(Arrays.asList(pair));
			default -> throw new IllegalStateException("not a value of YAML: " + value.getClass());
		};
	}

	/**
	 * A whole number as JSON, in the smallest kind it fits.
	 * @param number the number
	 * @return its JSON
	 */
	private static JsonNode whole(BigInteger number) {
		if (number.bitLength() < Integer.SIZE) {
			return NODES.numberNode(number.intValue());
		}
		return (number.bitLength() < Long.SIZE) ? NODES.numberNode(number.longValue()) : NODES.numberNode(number);
	}

	private static ObjectNode mapping(Map<?, ?> mapping) {
		ObjectNode object = NODES.objectNode();
		mapping.forEach((key, value) -> object.set(key(key), of(value)));
		return object;
	}

	private static ArrayNode sequence(Collection<?> items) {
		ArrayNode array = NODES.arrayNode();
		items.forEach((item) -> array.add(of(item)));
		return array;
	}

	/**
	 * A mapping's key as the text Python's {@code str()} gives it.
	 * @param key the key the constructor built
	 * @return its text
	 */
	private static String key(@Nullable Object key) {
		if (key instanceof Map<?, ?> || key instanceof Collection<?>) {
			throw new InvalidYamlException("while constructing a mapping: found unhashable key");
		}
		JsonNode value = of(key);
		return PythonText.str(value);
	}

}
