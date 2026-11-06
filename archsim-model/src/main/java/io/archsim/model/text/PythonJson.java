package io.archsim.model.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * JSON text exactly as Python's {@code json.dumps(value, separators=(",", ":"))} writes
 * it: no spaces, floats as their repr, NaN and the infinities by name, the keys in their
 * order or sorted by code point ({@code sort_keys=True}), and non-ASCII characters either
 * escaped (Python's default) or as they are ({@code ensure_ascii=False}).
 */
public final class PythonJson {

	/**
	 * The first character that is not a C0 control character; those before it are
	 * escaped.
	 */
	private static final char SPACE = 0x20;

	private static final PythonJson IN_ORDER_ASCII = new PythonJson(Keys.IN_ORDER, Characters.ASCII);

	private static final PythonJson IN_ORDER_AS_IS = new PythonJson(Keys.IN_ORDER, Characters.AS_IS);

	private final Keys keys;

	private final Characters characters;

	/**
	 * A writer with these options.
	 * @param keys the order of an object's keys
	 * @param characters which characters are written as they are
	 */
	PythonJson(Keys keys, Characters characters) {
		this.keys = keys;
		this.characters = characters;
	}

	/**
	 * The JSON text of a value with its keys in their order and in printable ASCII, as
	 * {@code json.dumps(value, separators=(",", ":"))} writes it: every other character
	 * is escaped with its four hexadecimal digits, a character beyond the Basic
	 * Multilingual Plane as its two surrogates.
	 * @param value the JSON value
	 * @return its text
	 */
	public static String write(JsonNode value) {
		return IN_ORDER_ASCII.text(value);
	}

	/**
	 * A string quoted and escaped as {@code json.dumps(text, ensure_ascii=False)} writes
	 * it.
	 * @param text the string
	 * @return its JSON text, quoted, with non-ASCII characters as they are
	 */
	public static String quote(String text) {
		return IN_ORDER_AS_IS.text(JsonNodeFactory.instance.stringNode(text));
	}

	/**
	 * The JSON text of a value, written with this writer's options.
	 * @param value the JSON value
	 * @return its text
	 */
	String text(JsonNode value) {
		StringBuilder out = new StringBuilder();
		append(value, out);
		return out.toString();
	}

	private void append(JsonNode value, StringBuilder out) {
		if (value.isNull() || value.isMissingNode()) {
			out.append("null");
		}
		else if (value.isBoolean()) {
			out.append(value.booleanValue());
		}
		else if (value.isIntegralNumber()) {
			out.append(value.bigIntegerValue());
		}
		else if (value.isNumber()) {
			out.append(number(value.doubleValue()));
		}
		else if (value.isString()) {
			string(value.stringValue(), out);
		}
		else if (value.isArray()) {
			array(value, out);
		}
		else {
			object(value, out);
		}
	}

	private void array(JsonNode array, StringBuilder out) {
		out.append('[');
		for (int i = 0; i < array.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			append(array.get(i), out);
		}
		out.append(']');
	}

	private void object(JsonNode object, StringBuilder out) {
		List<Map.Entry<String, JsonNode>> properties = new ArrayList<>(object.properties());
		if (this.keys == Keys.SORTED) {
			properties.sort(Map.Entry.comparingByKey(PythonJson::compareCodePoints));
		}
		out.append('{');
		for (int i = 0; i < properties.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			string(properties.get(i).getKey(), out);
			out.append(':');
			append(properties.get(i).getValue(), out);
		}
		out.append('}');
	}

	/**
	 * A float as Python's json module writes it: its repr, and NaN and the infinities by
	 * name.
	 * @param value the float
	 * @return its JSON text
	 */
	private static String number(double value) {
		if (Double.isNaN(value)) {
			return "NaN";
		}
		if (Double.isInfinite(value)) {
			return (value > 0) ? "Infinity" : "-Infinity";
		}
		return PythonText.repr(value);
	}

	/**
	 * A string as Python's json module writes it.
	 * @param text the string
	 * @param out where its JSON text is written, quoted
	 */
	private void string(String text, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < text.length(); i++) {
			char character = text.charAt(i);
			switch (character) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				default -> {
					if (character < SPACE || character > this.characters.lastUnescaped) {
						out.append("\\u%04x".formatted((int) character));
					}
					else {
						out.append(character);
					}
				}
			}
		}
		out.append('"');
	}

	/**
	 * Python's string order: by code point (Java's {@code compareTo} orders UTF-16
	 * units).
	 * @param left a string
	 * @param right another string
	 * @return below zero, zero or above zero as {@code left} comes before, with or after
	 * {@code right}
	 */
	private static int compareCodePoints(String left, String right) {
		int leftIndex = 0;
		int rightIndex = 0;
		while (leftIndex < left.length() && rightIndex < right.length()) {
			int leftCodePoint = left.codePointAt(leftIndex);
			int rightCodePoint = right.codePointAt(rightIndex);
			if (leftCodePoint != rightCodePoint) {
				return Integer.compare(leftCodePoint, rightCodePoint);
			}
			leftIndex += Character.charCount(leftCodePoint);
			rightIndex += Character.charCount(rightCodePoint);
		}
		return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
	}

	/**
	 * The order an object's keys are written in.
	 */
	enum Keys {

		/** In the object's order. */
		IN_ORDER,

		/** Sorted by code point ({@code sort_keys=True}). */
		SORTED

	}

	/**
	 * Which characters a string keeps as they are; the others, and the C0 control
	 * characters, are escaped.
	 */
	enum Characters {

		/** Printable ASCII, Python's default ({@code ensure_ascii=True}). */
		ASCII('~'),

		/** Every character but the control characters ({@code ensure_ascii=False}). */
		AS_IS(Character.MAX_VALUE);

		/** The last character written as it is; the characters after it are escaped. */
		private final char lastUnescaped;

		Characters(char lastUnescaped) {
			this.lastUnescaped = lastUnescaped;
		}

	}

}
