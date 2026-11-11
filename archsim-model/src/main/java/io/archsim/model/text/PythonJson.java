package io.archsim.model.text;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * JSON text exactly as Python's {@code json.dumps(value, separators=(",", ":"))} writes
 * it: no spaces, floats as their repr, NaN and the infinities by name, the keys in their
 * order or sorted by code point ({@code sort_keys=True}), and non-ASCII characters either
 * escaped (Python's default) or as they are ({@code ensure_ascii=False}); or, indented,
 * as {@code json.dumps(value, indent=n)} writes it; or as {@code pydantic_core.to_json}
 * writes it, which differs only in its floats.
 */
public final class PythonJson {

	/**
	 * The first character that is not a C0 control character; those before it are
	 * escaped.
	 */
	private static final char SPACE = 0x20;

	/**
	 * The exponent pydantic_core writes out in full: {@code 1e-05} is {@code 0.00001}.
	 */
	private static final int WRITTEN_OUT_EXPONENT = 5;

	private static final PythonJson IN_ORDER_ASCII = new PythonJson(Keys.IN_ORDER, Characters.ASCII);

	private static final PythonJson IN_ORDER_AS_IS = new PythonJson(Keys.IN_ORDER, Characters.AS_IS);

	private final Keys keys;

	private final Characters characters;

	private final Floats floats;

	/** One level of indentation; {@code null} for none, the whole value on one line. */
	private final @Nullable String indentation;

	/**
	 * A writer with these options, writing a value on one line.
	 * @param keys the order of an object's keys
	 * @param characters which characters are written as they are
	 */
	PythonJson(Keys keys, Characters characters) {
		this(keys, characters, Floats.REPR, null);
	}

	private PythonJson(Keys keys, Characters characters, Floats floats, @Nullable String indentation) {
		this.keys = keys;
		this.characters = characters;
		this.floats = floats;
		this.indentation = indentation;
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
	 * The JSON text of a value as {@code json.dumps(value, indent=spaces)} writes it: its
	 * keys in their order, each item of an array or object on a line of its own, indented
	 * by {@code spaces} for each level, and in printable ASCII as
	 * {@link #write(JsonNode)}.
	 * @param value the JSON value
	 * @param spaces the spaces each level is indented by
	 * @return its text
	 */
	public static String writeIndented(JsonNode value, int spaces) {
		return new PythonJson(Keys.IN_ORDER, Characters.ASCII, Floats.REPR, " ".repeat(spaces)).text(value);
	}

	/**
	 * The JSON text of a value as {@code pydantic_core.to_json(value, indent=spaces)}
	 * writes it, as the Python MCP SDK wrote a tool's result: laid out as
	 * {@link #writeIndented(JsonNode, int)} lays it out, every character but the control
	 * characters as it is, and a float as its repr but for its exponent: {@code -5}
	 * written out ({@code 0.00001}), lower ones without their zero ({@code 5e-6}).
	 * @param value the JSON value
	 * @param spaces the spaces each level is indented by
	 * @return its text
	 */
	public static String writePydantic(JsonNode value, int spaces) {
		return new PythonJson(Keys.IN_ORDER, Characters.AS_IS, Floats.PYDANTIC, " ".repeat(spaces)).text(value);
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
		append(value, out, 0);
		return out.toString();
	}

	private void append(JsonNode value, StringBuilder out, int depth) {
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
			out.append(this.floats.write(value.doubleValue()));
		}
		else if (value.isString()) {
			string(value.stringValue(), out);
		}
		else if (value.isArray()) {
			array(value, out, depth);
		}
		else {
			object(value, out, depth);
		}
	}

	// an empty array or object stays on one line, indented or not, as in Python
	private void array(JsonNode array, StringBuilder out, int depth) {
		if (array.isEmpty()) {
			out.append("[]");
			return;
		}
		out.append('[');
		for (int i = 0; i < array.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			newLine(out, depth + 1);
			append(array.get(i), out, depth + 1);
		}
		newLine(out, depth);
		out.append(']');
	}

	private void object(JsonNode object, StringBuilder out, int depth) {
		if (object.isEmpty()) {
			out.append("{}");
			return;
		}
		List<Map.Entry<String, JsonNode>> properties = new ArrayList<>(object.properties());
		if (this.keys == Keys.SORTED) {
			properties.sort(Map.Entry.comparingByKey(PythonJson::compareCodePoints));
		}
		out.append('{');
		for (int i = 0; i < properties.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			newLine(out, depth + 1);
			string(properties.get(i).getKey(), out);
			out.append((this.indentation != null) ? ": " : ":");
			append(properties.get(i).getValue(), out, depth + 1);
		}
		newLine(out, depth);
		out.append('}');
	}

	// indented, an item starts a line at its depth; on one line, nothing
	private void newLine(StringBuilder out, int depth) {
		if (this.indentation != null) {
			out.append('\n').append(this.indentation.repeat(depth));
		}
	}

	/**
	 * A float as Python's json module writes it: its repr, and NaN and the infinities by
	 * name.
	 * @param value the float
	 * @return its JSON text
	 */
	private static String repr(double value) {
		if (Double.isNaN(value)) {
			return "NaN";
		}
		if (Double.isInfinite(value)) {
			return (value > 0) ? "Infinity" : "-Infinity";
		}
		return PythonText.repr(value);
	}

	/**
	 * A float as pydantic_core writes it: as {@link #repr(double)}, but for an exponent
	 * of {@code -5} written out and a lower one without its zero.
	 * @param value the float
	 * @return its JSON text
	 */
	private static String pydantic(double value) {
		String repr = repr(value);
		int exponent = repr.indexOf("e-");
		if (exponent < 0) {
			return repr;
		}
		int power = Integer.parseInt(repr.substring(exponent + 2));
		if (power == WRITTEN_OUT_EXPONENT) {
			return new BigDecimal(repr).toPlainString();
		}
		return repr.substring(0, exponent + 2) + power;
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
	 * How floats are written.
	 */
	enum Floats {

		/** As their repr, as Python's json module writes them. */
		REPR,

		/** As pydantic_core writes them. */
		PYDANTIC;

		String write(double value) {
			return (this == PYDANTIC) ? pydantic(value) : repr(value);
		}

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
