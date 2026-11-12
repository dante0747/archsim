package io.archsim.model.codec;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A Python {@code bool}. Lax as pydantic is: {@code 0} and {@code 1}, and the strings
 * {@code "true"}, {@code "yes"}, {@code "on"}, {@code "1"} and their opposites (any case)
 * are booleans too.
 */
record BooleanShape() implements Shape {

	private static final Set<String> TRUE = Set.of("1", "on", "t", "true", "y", "yes");

	private static final Set<String> FALSE = Set.of("0", "off", "f", "false", "n", "no");

	@Override
	public @Nullable Object read(JsonNode in, Location at, Errors errors) {
		if (in.isBoolean()) {
			return in.booleanValue();
		}
		if (in.isString()) {
			return fromText(in.stringValue(), at, errors);
		}
		if (in.isIntegralNumber()) {
			return fromWord(in.bigIntegerValue().toString(), at, errors);
		}
		if (in.isNumber() && (in.doubleValue() == 0 || in.doubleValue() == 1)) {
			return in.doubleValue() == 1;
		}
		errors.add("bool_type", at, Messages.BOOL_TYPE);
		return null;
	}

	/**
	 * The boolean a text stands for, when pydantic can encode it to UTF-8 and it is one
	 * of the words.
	 */
	private static @Nullable Boolean fromText(String text, Location at, Errors errors) {
		if (Utf8Encoding.cannotEncode(text)) {
			Utf8Encoding.refuse(at, errors);
			return null;
		}
		return fromWord(text.toLowerCase(Locale.ROOT), at, errors);
	}

	private static @Nullable Boolean fromWord(String word, Location at, Errors errors) {
		if (TRUE.contains(word) || FALSE.contains(word)) {
			return TRUE.contains(word);
		}
		errors.add("bool_parsing", at, Messages.BOOL_PARSING);
		return null;
	}

	@Override
	public JsonNode write(@Nullable Object value) {
		return NODES.booleanNode((Boolean) Objects.requireNonNull(value));
	}

	@Override
	public ObjectNode schema(Definitions definitions) {
		return NODES.objectNode().put("type", "boolean");
	}
}
